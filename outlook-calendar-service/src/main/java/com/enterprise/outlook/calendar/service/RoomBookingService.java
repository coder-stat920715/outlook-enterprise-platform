package com.enterprise.outlook.calendar.service;

import com.enterprise.outlook.calendar.domain.Models.*;
import com.enterprise.outlook.common.util.Db;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Room / resource booking without double-booking, using THREE layers:
 *  1. Redisson distributed lock per resource  (serialises concurrent bookers across pods, cheap fast-fail)
 *  2. optimistic locking via version column   (safe reschedules)
 *  3. PostgreSQL EXCLUDE USING gist constraint (authoritative guarantee even if the lock is lost / Redis fails over)
 */
@Service
public class RoomBookingService {
    private final RedissonClient redisson;
    private final NamedParameterJdbcTemplate jdbc;

    public RoomBookingService(RedissonClient redisson, @Qualifier("directoryJdbc") NamedParameterJdbcTemplate jdbc) {
        this.redisson = redisson; this.jdbc = jdbc;
    }

    public UUID createResource(UUID tenant, ResourceRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resources (tenant_id, resource_id, name, kind, capacity, location) VALUES (:t, :id, :n, :k, :c, :l)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("id", id).addValue("n", r.name())
                        .addValue("k", r.kind() == null ? "ROOM" : r.kind()).addValue("c", r.capacity()).addValue("l", r.location()));
        return id;
    }

    public Booking book(UUID tenant, UUID resource, BookRequest r) {
        if (r.startAt() == null || r.endAt() == null || !r.endAt().isAfter(r.startAt()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid time range");
        return withLock(tenant, resource, () -> {
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("""
                        INSERT INTO resource_bookings (tenant_id, resource_id, booking_id, start_at, end_at, organizer_email, title)
                        VALUES (:t, :r, :id, :s, :e, :o, :title)""",
                        new MapSqlParameterSource().addValue("t", tenant).addValue("r", resource).addValue("id", id).addValue("s", Db.ts(r.startAt()))
                                .addValue("e", Db.ts(r.endAt())).addValue("o", r.organizerEmail()).addValue("title", r.title() == null ? "" : r.title()));
            } catch (DataIntegrityViolationException e) {                    // SQLSTATE 23P01 exclusion_violation
                throw new ResponseStatusException(HttpStatus.CONFLICT, "resource already booked for that time");
            }
            return new Booking(id, resource, r.startAt(), r.endAt(), r.organizerEmail(), r.title(), 0);
        });
    }

    public Booking reschedule(UUID tenant, UUID resource, UUID bookingId, RescheduleRequest r) {
        return withLock(tenant, resource, () -> {
            int n;
            try {
                n = jdbc.update("""
                        UPDATE resource_bookings SET start_at = :s, end_at = :e, version = version + 1
                        WHERE tenant_id = :t AND resource_id = :r AND booking_id = :id AND version = :v AND status = 'CONFIRMED'""",
                        new MapSqlParameterSource().addValue("s", Db.ts(r.startAt())).addValue("e", Db.ts(r.endAt())).addValue("t", tenant)
                                .addValue("r", resource).addValue("id", bookingId).addValue("v", r.version()));
            } catch (DataIntegrityViolationException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "resource already booked for that time");
            }
            if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "booking changed concurrently (version mismatch) or not found");
            return new Booking(bookingId, resource, r.startAt(), r.endAt(), null, null, r.version() + 1);
        });
    }

    public void cancel(UUID tenant, UUID resource, UUID bookingId) {
        jdbc.update("UPDATE resource_bookings SET status = 'CANCELLED', version = version + 1 WHERE tenant_id = :t AND resource_id = :r AND booking_id = :id",
                new MapSqlParameterSource().addValue("t", tenant).addValue("r", resource).addValue("id", bookingId));
    }

    private <T> T withLock(UUID tenant, UUID resource, java.util.function.Supplier<T> work) {
        RLock lock = redisson.getLock("lock:resource:" + tenant + ":" + resource);
        boolean locked = false;
        try {
            locked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (!locked) throw new ResponseStatusException(HttpStatus.CONFLICT, "resource is being booked by someone else, retry");
            return work.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
