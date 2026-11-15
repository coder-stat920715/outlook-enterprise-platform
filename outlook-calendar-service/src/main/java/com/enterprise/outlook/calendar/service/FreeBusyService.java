package com.enterprise.outlook.calendar.service;

import com.enterprise.outlook.calendar.domain.Models.*;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Free/busy engine. Each (mailbox, UTC day) is a 96-bit bitmap (15-minute slots) cached in Redis as 12 bytes.
 * Invalidation is O(1): bump a per-mailbox version that is part of the key (old keys expire by TTL).
 * Supports multi-attendee lookups and cross-tenant targets (when enabled by policy).
 */
@Service
public class FreeBusyService {
    static final int SLOT_MINUTES = 15;
    static final int SLOTS_PER_DAY = 96;

    private final StringRedisTemplate redis;
    private final OccurrenceExpander expander;
    private final boolean crossTenantEnabled;

    public FreeBusyService(StringRedisTemplate redis, OccurrenceExpander expander,
                           @org.springframework.beans.factory.annotation.Value("${outlook.calendar.cross-tenant-freebusy:false}") boolean crossTenantEnabled) {
        this.redis = redis; this.expander = expander; this.crossTenantEnabled = crossTenantEnabled;
    }

    private String verKey(UUID t, UUID m) { return "fb:ver:" + t + ":" + m; }

    private long version(UUID t, UUID m) {
        try { String v = redis.opsForValue().get(verKey(t, m)); return v == null ? 0 : Long.parseLong(v); } catch (RuntimeException e) { return 0; }
    }

    public void invalidate(UUID t, UUID m) {
        try { redis.opsForValue().increment(verKey(t, m)); } catch (RuntimeException ignored) { /* TTL bounds staleness */ }
    }

    private BitSet dayBits(UUID t, UUID m, LocalDate day, long ver) {
        byte[] key = ("fb:" + t + ":" + m + ":" + ver + ":" + day).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] cached = redis.execute((org.springframework.data.redis.core.RedisCallback<byte[]>) c -> c.stringCommands().get(key));
            if (cached != null) return BitSet.valueOf(cached);
        } catch (RuntimeException ignored) { /* fall through to rebuild */ }

        Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = dayStart.plus(Duration.ofDays(1));
        BitSet bits = new BitSet(SLOTS_PER_DAY);
        for (Occurrence o : expander.window(t, m, dayStart, dayEnd)) {
            if (!"BUSY".equals(o.showAs()) && !"OOF".equals(o.showAs()) && !"TENTATIVE".equals(o.showAs())) continue;
            long s = Math.max(0, Duration.between(dayStart, o.start()).toMinutes() / SLOT_MINUTES);
            long e = Math.min(SLOTS_PER_DAY, (Duration.between(dayStart, o.end()).toMinutes() + SLOT_MINUTES - 1) / SLOT_MINUTES);
            if (e > s) bits.set((int) s, (int) e);
        }
        byte[] raw = Arrays.copyOf(bits.toByteArray(), SLOTS_PER_DAY / 8);
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c ->
                    c.stringCommands().set(key, raw, Expiration.seconds(3600), RedisStringCommands.SetOption.UPSERT));
        } catch (RuntimeException ignored) { }
        return bits;
    }

    /** Merged busy intervals of one mailbox in [start, end). */
    public List<Interval> busy(UUID t, UUID m, Instant start, Instant end) {
        long ver = version(t, m);
        List<Interval> out = new ArrayList<>();
        for (LocalDate d = LocalDate.ofInstant(start, ZoneOffset.UTC); !d.atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(end); d = d.plusDays(1)) {
            BitSet bits = dayBits(t, m, d, ver);
            Instant base = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i)) {
                int j = bits.nextClearBit(i);
                Instant s = base.plus(Duration.ofMinutes((long) i * SLOT_MINUTES)), e = base.plus(Duration.ofMinutes((long) j * SLOT_MINUTES));
                if (e.isAfter(start) && s.isBefore(end)) out.add(new Interval(s.isBefore(start) ? start : s, e.isAfter(end) ? end : e));
                i = j;
            }
        }
        return merge(out);
    }

    public FreeBusyResponse query(UUID callerTenant, FreeBusyRequest req) {
        if (req.mailboxes() == null || req.mailboxes().isEmpty() || req.mailboxes().size() > 50)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "1..50 mailboxes required");
        if (Duration.between(req.start(), req.end()).toDays() > 62) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "window too large");
        Map<UUID, List<Interval>> busy = new LinkedHashMap<>();
        List<Interval> union = new ArrayList<>();
        for (FreeBusyTarget target : req.mailboxes()) {
            UUID tenant = target.tenantId() == null ? callerTenant : target.tenantId();
            if (!tenant.equals(callerTenant) && !crossTenantEnabled)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-tenant free/busy is disabled by policy");
            List<Interval> b = busy(tenant, target.mailboxId(), req.start(), req.end());
            busy.put(target.mailboxId(), b);
            union.addAll(b);
        }
        return new FreeBusyResponse(busy, commonFree(merge(union), req.start(), req.end(), Math.max(15, req.durationMinutes())));
    }

    static List<Interval> merge(List<Interval> in) {
        List<Interval> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparing(Interval::start));
        List<Interval> out = new ArrayList<>();
        for (Interval i : sorted) {
            if (!out.isEmpty() && !i.start().isAfter(out.get(out.size() - 1).end())) {
                Interval last = out.remove(out.size() - 1);
                out.add(new Interval(last.start(), i.end().isAfter(last.end()) ? i.end() : last.end()));
            } else out.add(i);
        }
        return out;
    }

    static List<Interval> commonFree(List<Interval> mergedBusy, Instant start, Instant end, int minutes) {
        List<Interval> free = new ArrayList<>();
        Instant cursor = start;
        for (Interval b : mergedBusy) {
            if (Duration.between(cursor, b.start()).toMinutes() >= minutes) free.add(new Interval(cursor, b.start()));
            if (b.end().isAfter(cursor)) cursor = b.end();
        }
        if (Duration.between(cursor, end).toMinutes() >= minutes) free.add(new Interval(cursor, end));
        return free.size() > 10 ? free.subList(0, 10) : free;
    }
}
