package com.enterprise.outlook.calendar.service;

import com.enterprise.outlook.calendar.domain.Models.*;
import com.enterprise.outlook.calendar.domain.RRuleExpander;
import com.enterprise.outlook.calendar.repo.EventRepository;
import com.enterprise.outlook.common.model.MailEnvelope;
import com.enterprise.outlook.common.model.SyncEvent;
import com.enterprise.outlook.common.model.Topics;
import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.common.util.Json;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@Service
public class CalendarService {
    private record Person(UUID mailboxId, String email, String name) {}

    private final ShardExecutor shards;
    private final EventRepository events;
    private final OccurrenceExpander expander;
    private final FreeBusyService freeBusy;
    private final MeetingMailer mailer;
    private final NamedParameterJdbcTemplate directory;
    private final KafkaTemplate<String, String> kafka;

    public CalendarService(ShardExecutor shards, EventRepository events, OccurrenceExpander expander, FreeBusyService freeBusy,
                           MeetingMailer mailer, @Qualifier("directoryJdbc") NamedParameterJdbcTemplate directory,
                           KafkaTemplate<String, String> kafka) {
        this.shards = shards; this.events = events; this.expander = expander; this.freeBusy = freeBusy;
        this.mailer = mailer; this.directory = directory; this.kafka = kafka;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------
    private Person me(UUID tenant, UUID mailbox) {
        return directory.query("SELECT email, display_name FROM directory_mailboxes WHERE tenant_id = :t AND mailbox_id = :m",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox),
                (rs, i) -> new Person(mailbox, rs.getString(1), rs.getString(2))).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "mailbox"));
    }

    private Optional<Person> byEmail(UUID tenant, String email) {
        return directory.query("SELECT mailbox_id, email, display_name FROM directory_mailboxes WHERE tenant_id = :t AND email = :e",
                new MapSqlParameterSource().addValue("t", tenant).addValue("e", email.toLowerCase()),
                (rs, i) -> new Person(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).stream().findFirst();
    }

    private void validate(Instant s, Instant e, String tz, String rrule) {
        if (s == null || e == null || !e.isAfter(s)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "end must be after start");
        if (Duration.between(s, e).compareTo(EventRepository.MAX_EVENT_DURATION) > 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "event longer than 31 days");
        try { ZoneId.of(tz); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "time_zone must be an IANA TZDB id"); }
        if (rrule != null && !rrule.isBlank()) {
            try { RRuleExpander.parse(rrule); } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid RRULE: " + ex.getMessage()); }
        }
    }

    /** Last instant covered by the series (null = unbounded) so recurring-window queries can be bounded. */
    private Instant seriesUntil(Instant start, Instant end, String tz, String rrule) {
        if (rrule == null || rrule.isBlank()) return null;
        var rule = RRuleExpander.parse(rrule);
        if (rule.count() != null) {
            var occ = RRuleExpander.expand(start, end, ZoneId.of(tz), rule, Set.of(), start, start.plus(Duration.ofDays(365L * 50)), rule.count());
            return occ.isEmpty() ? end : occ.get(occ.size() - 1).end();
        }
        return rule.until();
    }

    private SyncEvent recordChange(UUID t, UUID m, UUID eventId, String type) {
        long seq = events.nextSeq(t, m);
        events.appendLog(t, m, seq, eventId, type);
        return new SyncEvent(t, m, seq, "EVENT", eventId, type, null, Instant.now());
    }

    private void publish(List<SyncEvent> evs) {
        for (SyncEvent e : evs) kafka.send(Topics.SYNC_EVENTS, e.tenantId() + ":" + e.mailboxId(), Json.write(e));
    }

    // ---- operations -------------------------------------------------------------------------------------------------------------
    public CalendarEvent create(UUID tenant, UUID mailbox, CreateEventRequest r) {
        validate(r.startAt(), r.endAt(), r.timeZone(), r.rrule());
        Person org = me(tenant, mailbox);
        UUID id = UUID.randomUUID();
        List<Attendee> atts = r.attendees() == null ? List.of() : r.attendees().stream()
                .map(a -> new Attendee(a.email().toLowerCase(), a.name(), Response.NEEDS_ACTION)).toList();
        MeetingStatus status = atts.isEmpty() ? MeetingStatus.DRAFT : (r.sendInvites() ? MeetingStatus.SENT : MeetingStatus.DRAFT);
        CalendarEvent e = new CalendarEvent(id, id + "@outlook.enterprise", r.title(), r.location(), r.startAt(), r.endAt(), r.timeZone(), r.allDay(),
                r.rrule(), r.exdates() == null ? List.of() : r.exdates(), seriesUntil(r.startAt(), r.endAt(), r.timeZone(), r.rrule()),
                org.email(), atts, status, 0, r.showAs() == null ? "BUSY" : r.showAs(), 0);
        SyncEvent ev = shards.transact(tenant, mailbox, () -> { events.upsert(tenant, mailbox, e); return recordChange(tenant, mailbox, id, "CREATED"); });
        publish(List.of(ev));
        freeBusy.invalidate(tenant, mailbox);
        if (status == MeetingStatus.SENT) distribute(tenant, mailbox, org, e, "REQUEST");
        return e;
    }

    public CalendarEvent update(UUID tenant, UUID mailbox, UUID eventId, UpdateEventRequest r) {
        CalendarEvent cur = get(tenant, mailbox, eventId);
        if (cur.meetingStatus() == MeetingStatus.CANCELLED) throw new ResponseStatusException(HttpStatus.CONFLICT, "meeting is cancelled");
        validate(r.startAt() != null ? r.startAt() : cur.startAt(), r.endAt() != null ? r.endAt() : cur.endAt(),
                r.timeZone() != null ? r.timeZone() : cur.timeZone(), r.rrule() != null ? r.rrule() : cur.rrule());
        Instant s = r.startAt() != null ? r.startAt() : cur.startAt(), en = r.endAt() != null ? r.endAt() : cur.endAt();
        String tz = r.timeZone() != null ? r.timeZone() : cur.timeZone();
        String rr = r.rrule() != null ? r.rrule() : cur.rrule();
        boolean invited = !cur.attendees().isEmpty() && cur.meetingStatus() != MeetingStatus.DRAFT;
        List<Attendee> atts = r.attendees() != null ? r.attendees().stream().map(a -> new Attendee(a.email().toLowerCase(), a.name(),
                cur.attendees().stream().filter(x -> x.email().equalsIgnoreCase(a.email())).map(Attendee::response).findFirst().orElse(Response.NEEDS_ACTION))).toList()
                : cur.attendees();
        CalendarEvent upd = new CalendarEvent(eventId, cur.icsUid(), r.title() != null ? r.title() : cur.title(), r.location() != null ? r.location() : cur.location(),
                s, en, tz, cur.allDay(), rr, r.exdates() != null ? r.exdates() : cur.exdates(), seriesUntil(s, en, tz, rr), cur.organizerEmail(), atts,
                invited ? cur.meetingStatus().transition(MeetingStatus.UPDATED) : cur.meetingStatus(), cur.sequenceNo() + (invited ? 1 : 0), cur.showAs(), cur.version());
        SyncEvent ev = shards.transact(tenant, mailbox, () -> {
            if (!events.update(tenant, mailbox, upd, r.version())) throw new ResponseStatusException(HttpStatus.CONFLICT, "event was modified concurrently");
            return recordChange(tenant, mailbox, eventId, "UPDATED");
        });
        publish(List.of(ev));
        freeBusy.invalidate(tenant, mailbox);
        if (invited) distribute(tenant, mailbox, me(tenant, mailbox), upd, "REQUEST");
        return get(tenant, mailbox, eventId);
    }

    public void cancel(UUID tenant, UUID mailbox, UUID eventId) {
        CalendarEvent cur = get(tenant, mailbox, eventId);
        CalendarEvent cancelled = new CalendarEvent(eventId, cur.icsUid(), cur.title(), cur.location(), cur.startAt(), cur.endAt(), cur.timeZone(), cur.allDay(),
                cur.rrule(), cur.exdates(), cur.seriesUntil(), cur.organizerEmail(), cur.attendees(), cur.meetingStatus().transition(MeetingStatus.CANCELLED),
                cur.sequenceNo() + 1, cur.showAs(), cur.version());
        SyncEvent ev = shards.transact(tenant, mailbox, () -> {
            if (!events.update(tenant, mailbox, cancelled, cur.version())) throw new ResponseStatusException(HttpStatus.CONFLICT, "event was modified concurrently");
            return recordChange(tenant, mailbox, eventId, "DELETED");
        });
        publish(List.of(ev));
        freeBusy.invalidate(tenant, mailbox);
        if (!cur.attendees().isEmpty()) distribute(tenant, mailbox, me(tenant, mailbox), cancelled, "CANCEL");
    }

    /** Attendee RSVP: updates the attendee's own copy and the organizer's attendee list, then mails an iCalendar REPLY. */
    public void respond(UUID tenant, UUID attendeeMailbox, UUID eventId, Response response) {
        CalendarEvent own = get(tenant, attendeeMailbox, eventId);
        Person attendee = me(tenant, attendeeMailbox);
        String showAs = response == Response.ACCEPTED ? "BUSY" : response == Response.TENTATIVE ? "TENTATIVE" : "FREE";
        CalendarEvent ownUpd = new CalendarEvent(own.eventId(), own.icsUid(), own.title(), own.location(), own.startAt(), own.endAt(), own.timeZone(), own.allDay(),
                own.rrule(), own.exdates(), own.seriesUntil(), own.organizerEmail(), own.attendees(), own.meetingStatus(), own.sequenceNo(), showAs, own.version());
        SyncEvent ev = shards.transact(tenant, attendeeMailbox, () -> {
            if (!events.update(tenant, attendeeMailbox, ownUpd, own.version())) throw new ResponseStatusException(HttpStatus.CONFLICT, "concurrent update");
            return recordChange(tenant, attendeeMailbox, eventId, "UPDATED");
        });
        publish(List.of(ev));
        freeBusy.invalidate(tenant, attendeeMailbox);

        byEmail(tenant, own.organizerEmail()).ifPresent(org -> shards.transact(tenant, org.mailboxId(), () -> {
            events.findByUid(tenant, org.mailboxId(), own.icsUid()).ifPresent(oe -> {
                List<Attendee> list = oe.attendees().stream().map(a -> a.email().equalsIgnoreCase(attendee.email()) ? new Attendee(a.email(), a.name(), response) : a).toList();
                CalendarEvent updated = new CalendarEvent(oe.eventId(), oe.icsUid(), oe.title(), oe.location(), oe.startAt(), oe.endAt(), oe.timeZone(), oe.allDay(),
                        oe.rrule(), oe.exdates(), oe.seriesUntil(), oe.organizerEmail(), list, oe.meetingStatus(), oe.sequenceNo(), oe.showAs(), oe.version());
                events.update(tenant, org.mailboxId(), updated, oe.version());
            });
            return null;
        }));
        byEmail(tenant, own.organizerEmail()).ifPresent(org ->
                mailer.send(tenant, attendeeMailbox, attendee.email(), attendee.name(), own, "REPLY", List.of(new MailEnvelope.Recipient(org.email(), org.name())), response));
    }

    /** Creates a TENTATIVE copy in every internal attendee's calendar and mails the iCalendar to all attendees. */
    private void distribute(UUID tenant, UUID organizerMailbox, Person organizer, CalendarEvent e, String method) {
        List<MailEnvelope.Recipient> to = new ArrayList<>();
        for (Attendee a : e.attendees()) {
            to.add(new MailEnvelope.Recipient(a.email(), a.name()));
            byEmail(tenant, a.email()).ifPresent(p -> {
                if (p.mailboxId().equals(organizerMailbox)) return;
                CalendarEvent copy = new CalendarEvent(UUID.randomUUID(), e.icsUid(), e.title(), e.location(), e.startAt(), e.endAt(), e.timeZone(), e.allDay(), e.rrule(),
                        e.exdates(), e.seriesUntil(), e.organizerEmail(), e.attendees(), e.meetingStatus(), e.sequenceNo(),
                        "CANCEL".equals(method) ? "FREE" : "TENTATIVE", 0);
                SyncEvent ev = shards.transact(tenant, p.mailboxId(), () -> { events.upsert(tenant, p.mailboxId(), copy); return recordChange(tenant, p.mailboxId(), copy.eventId(), "CANCEL".equals(method) ? "DELETED" : "CREATED"); });
                publish(List.of(ev));
                freeBusy.invalidate(tenant, p.mailboxId());
            });
        }
        mailer.send(tenant, organizerMailbox, organizer.email(), organizer.name(), e, method, to, null);
    }

    public CalendarEvent get(UUID tenant, UUID mailbox, UUID eventId) {
        return shards.read(tenant, mailbox, () -> events.find(tenant, mailbox, eventId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "event"));
    }

    public List<Occurrence> window(UUID tenant, UUID mailbox, Instant from, Instant to) {
        if (Duration.between(from, to).toDays() > 400) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "window too large");
        return expander.window(tenant, mailbox, from, to);
    }
}
