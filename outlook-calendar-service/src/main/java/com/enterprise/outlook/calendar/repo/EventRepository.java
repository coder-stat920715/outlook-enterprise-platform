package com.enterprise.outlook.calendar.repo;

import com.enterprise.outlook.calendar.domain.Models.*;
import com.enterprise.outlook.common.util.Db;
import com.enterprise.outlook.common.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shard-bound (call inside ShardExecutor). All window queries are bounded on start_at so the index range scan stays tight. */
@Repository
public class EventRepository {
    public static final Duration MAX_EVENT_DURATION = Duration.ofDays(31);
    private static final TypeReference<List<Instant>> INSTANTS = new TypeReference<>() {};
    private static final TypeReference<List<Attendee>> ATTENDEES = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;

    public EventRepository(@Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "event_id, ics_uid, title, location, start_at, end_at, time_zone, all_day, rrule, CAST(exdates AS text) AS ex, "
            + "series_until, organizer_email, CAST(attendees AS text) AS att, meeting_status, sequence_no, show_as, version";

    private static final RowMapper<CalendarEvent> MAP = (rs, i) -> {
        try {
            return new CalendarEvent(rs.getObject("event_id", UUID.class), rs.getString("ics_uid"), rs.getString("title"), rs.getString("location"),
                    Db.instant(rs, "start_at"), Db.instant(rs, "end_at"), rs.getString("time_zone"), rs.getBoolean("all_day"), rs.getString("rrule"),
                    Json.MAPPER.readValue(rs.getString("ex"), INSTANTS), Db.instant(rs, "series_until"), rs.getString("organizer_email"),
                    Json.MAPPER.readValue(rs.getString("att"), ATTENDEES), MeetingStatus.valueOf(rs.getString("meeting_status")),
                    rs.getInt("sequence_no"), rs.getString("show_as"), rs.getLong("version"));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    };

    private static MapSqlParameterSource p(UUID t, UUID m) { return new MapSqlParameterSource().addValue("t", t).addValue("m", m); }

    /** Upsert by ICS UID so re-delivered invites update the existing copy instead of duplicating it. */
    public void upsert(UUID t, UUID m, CalendarEvent e) {
        jdbc.update("""
                INSERT INTO calendar_events (tenant_id, mailbox_id, event_id, ics_uid, title, location, start_at, end_at, time_zone, all_day, rrule,
                    exdates, series_until, organizer_email, attendees, meeting_status, sequence_no, show_as)
                VALUES (:t, :m, :id, :uid, :title, :loc, :s, :e, :tz, :ad, :rr, CAST(:ex AS jsonb), :su, :org, CAST(:att AS jsonb), :ms, :seq, :show)
                ON CONFLICT (tenant_id, mailbox_id, ics_uid) DO UPDATE SET title = :title, location = :loc, start_at = :s, end_at = :e, time_zone = :tz,
                    rrule = :rr, exdates = CAST(:ex AS jsonb), series_until = :su, attendees = CAST(:att AS jsonb), meeting_status = :ms,
                    sequence_no = :seq, version = calendar_events.version + 1""",
                p(t, m).addValue("id", e.eventId()).addValue("uid", e.icsUid()).addValue("title", e.title()).addValue("loc", e.location())
                        .addValue("s", Db.ts(e.startAt())).addValue("e", Db.ts(e.endAt())).addValue("tz", e.timeZone()).addValue("ad", e.allDay())
                        .addValue("rr", e.rrule()).addValue("ex", Json.write(e.exdates())).addValue("su", Db.ts(e.seriesUntil()))
                        .addValue("org", e.organizerEmail()).addValue("att", Json.write(e.attendees())).addValue("ms", e.meetingStatus().name())
                        .addValue("seq", e.sequenceNo()).addValue("show", e.showAs()));
    }

    public Optional<CalendarEvent> find(UUID t, UUID m, UUID eventId) {
        return jdbc.query("SELECT " + COLS + " FROM calendar_events WHERE tenant_id = :t AND mailbox_id = :m AND event_id = :id",
                p(t, m).addValue("id", eventId), MAP).stream().findFirst();
    }

    public Optional<CalendarEvent> findByUid(UUID t, UUID m, String uid) {
        return jdbc.query("SELECT " + COLS + " FROM calendar_events WHERE tenant_id = :t AND mailbox_id = :m AND ics_uid = :u",
                p(t, m).addValue("u", uid), MAP).stream().findFirst();
    }

    /** Optimistic update; returns false on version conflict. */
    public boolean update(UUID t, UUID m, CalendarEvent e, long expectedVersion) {
        return jdbc.update("""
                UPDATE calendar_events SET title = :title, location = :loc, start_at = :s, end_at = :e, time_zone = :tz, rrule = :rr,
                    exdates = CAST(:ex AS jsonb), series_until = :su, attendees = CAST(:att AS jsonb), meeting_status = :ms,
                    sequence_no = :seq, show_as = :show, version = version + 1
                WHERE tenant_id = :t AND mailbox_id = :m AND event_id = :id AND version = :v""",
                p(t, m).addValue("id", e.eventId()).addValue("title", e.title()).addValue("loc", e.location())
                        .addValue("s", Db.ts(e.startAt())).addValue("e", Db.ts(e.endAt())).addValue("tz", e.timeZone()).addValue("rr", e.rrule())
                        .addValue("ex", Json.write(e.exdates())).addValue("su", Db.ts(e.seriesUntil())).addValue("att", Json.write(e.attendees()))
                        .addValue("ms", e.meetingStatus().name()).addValue("seq", e.sequenceNo()).addValue("show", e.showAs()).addValue("v", expectedVersion)) == 1;
    }

    public List<CalendarEvent> singles(UUID t, UUID m, Instant from, Instant to) {
        return jdbc.query("SELECT " + COLS + """
                 FROM calendar_events WHERE tenant_id = :t AND mailbox_id = :m AND rrule IS NULL
                  AND start_at >= :lo AND start_at < :to AND end_at > :from AND meeting_status <> 'CANCELLED'""",
                p(t, m).addValue("lo", Db.ts(from.minus(MAX_EVENT_DURATION))).addValue("to", Db.ts(to)).addValue("from", Db.ts(from)), MAP);
    }

    public List<CalendarEvent> recurring(UUID t, UUID m, Instant from, Instant to) {
        return jdbc.query("SELECT " + COLS + """
                 FROM calendar_events WHERE tenant_id = :t AND mailbox_id = :m AND rrule IS NOT NULL
                  AND start_at < :to AND (series_until IS NULL OR series_until >= :from) AND meeting_status <> 'CANCELLED'""",
                p(t, m).addValue("to", Db.ts(to)).addValue("from", Db.ts(from)), MAP);
    }

    // ---- delta log (calendar changes share the mailbox change sequence) ------------------------------------------------
    public long nextSeq(UUID t, UUID m) {
        return jdbc.queryForObject("""
                INSERT INTO mailbox_sync_state (tenant_id, mailbox_id, last_seq) VALUES (:t, :m, 1)
                ON CONFLICT (tenant_id, mailbox_id) DO UPDATE SET last_seq = mailbox_sync_state.last_seq + 1 RETURNING last_seq""", p(t, m), Long.class);
    }

    public void appendLog(UUID t, UUID m, long seq, UUID entityId, String changeType) {
        jdbc.update("""
                INSERT INTO delta_sync_log (tenant_id, mailbox_id, change_seq, entity_type, entity_id, change_type)
                VALUES (:t, :m, :s, 'EVENT', :e, :c)""", p(t, m).addValue("s", seq).addValue("e", entityId).addValue("c", changeType));
    }
}
