package com.enterprise.outlook.calendar.service;

import com.enterprise.outlook.calendar.domain.Models.CalendarEvent;
import com.enterprise.outlook.calendar.domain.Models.Occurrence;
import com.enterprise.outlook.calendar.domain.RRuleExpander;
import com.enterprise.outlook.calendar.repo.EventRepository;
import com.enterprise.outlook.common.sharding.ShardExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Loads single + recurring events for a window and expands recurrences (DST-aware). */
@Component
public class OccurrenceExpander {
    private final ShardExecutor shards;
    private final EventRepository events;

    public OccurrenceExpander(ShardExecutor shards, EventRepository events) { this.shards = shards; this.events = events; }

    public List<Occurrence> window(UUID tenant, UUID mailbox, Instant from, Instant to) {
        var loaded = shards.read(tenant, mailbox, () -> {
            List<CalendarEvent> all = new ArrayList<>(events.singles(tenant, mailbox, from, to));
            all.addAll(events.recurring(tenant, mailbox, from, to));
            return all;
        });
        List<Occurrence> out = new ArrayList<>();
        for (CalendarEvent e : loaded) {
            if (!e.recurring()) { out.add(new Occurrence(e.eventId(), e.title(), e.startAt(), e.endAt(), false, e.showAs(), e.meetingStatus().name())); continue; }
            var rule = RRuleExpander.parse(e.rrule());
            for (var o : RRuleExpander.expand(e.startAt(), e.endAt(), ZoneId.of(e.timeZone()), rule, new HashSet<>(e.exdates()), from, to, 5_000))
                out.add(new Occurrence(e.eventId(), e.title(), o.start(), o.end(), true, e.showAs(), e.meetingStatus().name()));
        }
        out.sort(Comparator.comparing(Occurrence::start));
        return out;
    }
}
