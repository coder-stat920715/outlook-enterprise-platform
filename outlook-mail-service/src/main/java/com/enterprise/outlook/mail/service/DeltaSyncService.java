package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.model.SyncEvent;
import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.mail.config.MailProperties;
import com.enterprise.outlook.mail.dto.Dtos.DeltaChange;
import com.enterprise.outlook.mail.dto.Dtos.DeltaResponse;
import com.enterprise.outlook.mail.kafka.MailPublisher;
import com.enterprise.outlook.mail.repo.SyncRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delta sync engine (Graph /messages/delta style). Clients hold an opaque token = last change_seq they applied.
 * The per-mailbox watermark lives in Redis so the overwhelmingly common "nothing changed" poll never touches PostgreSQL.
 */
@Service
public class DeltaSyncService {
    private final ShardExecutor shards;
    private final SyncRepository sync;
    private final StringRedisTemplate redis;
    private final MailPublisher publisher;
    private final String secret;

    public DeltaSyncService(ShardExecutor shards, SyncRepository sync, StringRedisTemplate redis, MailPublisher publisher, MailProperties props) {
        this.shards = shards; this.sync = sync; this.redis = redis; this.publisher = publisher; this.secret = props.deltaSecret();
    }

    private static String wmKey(UUID t, UUID m) { return "delta:wm:" + t + ":" + m; }

    /** Called INSIDE the shard transaction: allocates seq + writes the log row. */
    public SyncEvent record(UUID t, UUID m, String entityType, UUID entityId, String changeType, UUID folderId) {
        long seq = sync.nextSeq(t, m);
        sync.append(t, m, seq, entityType, entityId, changeType, folderId);
        return new SyncEvent(t, m, seq, entityType, entityId, changeType, folderId, Instant.now());
    }

    /** Called AFTER commit: advance the Redis watermark and fan out the push event. */
    public void publishAfterCommit(List<SyncEvent> events) {
        for (SyncEvent e : events) {
            try { redis.opsForValue().set(wmKey(e.tenantId(), e.mailboxId()), Long.toString(e.changeSeq()), Duration.ofDays(7)); }
            catch (RuntimeException ignored) { /* watermark is a cache; DB remains the source of truth */ }
            publisher.syncEvent(e);
        }
    }

    public DeltaResponse fetch(UUID t, UUID m, String token, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        if (token == null || token.isBlank()) {   // initial sync: client pages the folders, then continues from the returned token
            long last = currentSeq(t, m);
            return new DeltaResponse(List.of(), DeltaToken.encode(t, m, last, secret), false, true);
        }
        long since = DeltaToken.decode(token, t, m, secret);
        String wm = null;
        try { wm = redis.opsForValue().get(wmKey(t, m)); } catch (RuntimeException ignored) { }
        if (wm != null && Long.parseLong(wm) == since) return new DeltaResponse(List.of(), token, false, false);   // fast path: no DB hit

        return shards.read(t, m, () -> {
            if (since < sync.minRetainedSeq(t, m)) throw new ResponseStatusException(HttpStatus.GONE, "deltaToken expired: perform a full resync");
            List<DeltaChange> rows = sync.findAfter(t, m, since, lim + 1);
            boolean more = rows.size() > lim;
            List<DeltaChange> page = more ? rows.subList(0, lim) : rows;
            long next = page.isEmpty() ? since : page.get(page.size() - 1).changeSeq();
            return new DeltaResponse(page, DeltaToken.encode(t, m, next, secret), more, false);
        });
    }

    private long currentSeq(UUID t, UUID m) {
        try {
            String wm = redis.opsForValue().get(wmKey(t, m));
            if (wm != null) return Long.parseLong(wm);
        } catch (RuntimeException ignored) { }
        return shards.read(t, m, () -> sync.lastSeq(t, m));
    }
}
