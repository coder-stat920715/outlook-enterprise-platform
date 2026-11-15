package com.enterprise.outlook.calendar;

import com.enterprise.outlook.calendar.domain.IcsBuilder;
import com.enterprise.outlook.calendar.domain.Models.*;
import com.enterprise.outlook.calendar.domain.RRuleExpander;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RRuleExpanderTest {
    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void weeklyMeetingStaysAtNineLocalAcrossDstEnd() {
        Instant s = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, NY).toInstant();        // EDT (UTC-4)
        var occ = RRuleExpander.expand(s, s.plusSeconds(3600), NY, RRuleExpander.parse("FREQ=WEEKLY;BYDAY=MO"), Set.of(), s, s.plus(Duration.ofDays(21)), 10);
        assertEquals(3, occ.size());
        assertEquals(Instant.parse("2026-10-26T13:00:00Z"), occ.get(0).start());
        assertEquals(Instant.parse("2026-11-02T14:00:00Z"), occ.get(1).start());        // EST (UTC-5) after Nov 1 2026
        occ.forEach(o -> assertEquals(LocalTime.of(9, 0), o.start().atZone(NY).toLocalTime()));
    }

    @Test
    void ordinalWeekdayCountAndExdate() {
        Instant s = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, NY).toInstant();
        var m = RRuleExpander.expand(s, s.plusSeconds(3600), NY, RRuleExpander.parse("FREQ=MONTHLY;BYDAY=2TU;COUNT=3"), Set.of(), s, s.plus(Duration.ofDays(400)), 10);
        assertEquals(List.of(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 8), LocalDate.of(2027, 1, 12)),
                m.stream().map(o -> o.start().atZone(NY).toLocalDate()).toList());
        var d = RRuleExpander.expand(s, s.plusSeconds(3600), NY, RRuleExpander.parse("FREQ=DAILY;COUNT=5"), Set.of(s.plus(Duration.ofDays(1))), s, s.plus(Duration.ofDays(30)), 10);
        assertEquals(4, d.size());
    }

    @Test
    void leapDayYearlySkipsNonLeapYears() {
        Instant s = ZonedDateTime.of(2028, 2, 29, 9, 0, 0, 0, NY).toInstant();
        var o = RRuleExpander.expand(s, s.plusSeconds(60), NY, RRuleExpander.parse("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;COUNT=2"), Set.of(), s, s.plus(Duration.ofDays(3000)), 10);
        assertEquals(LocalDate.of(2032, 2, 29), o.get(1).start().atZone(NY).toLocalDate());
    }

    @Test
    void icsUsesTzidAndFolds() {
        var e = new CalendarEvent(UUID.randomUUID(), "uid@x", "A very long title, with; separators " + "x".repeat(120), null,
                Instant.parse("2026-10-26T13:00:00Z"), Instant.parse("2026-10-26T14:00:00Z"), "America/New_York", false, "FREQ=WEEKLY;BYDAY=MO",
                List.of(), null, "o@corp.com", List.of(new Attendee("a@corp.com", "A", Response.NEEDS_ACTION)), MeetingStatus.SENT, 0, "BUSY", 0);
        String ics = IcsBuilder.build("REQUEST", e, null, null);
        assertTrue(ics.contains("DTSTART;TZID=America/New_York:20261026T090000"));
        assertTrue(ics.contains("METHOD:REQUEST"));
        for (String line : ics.split("\r\n")) assertTrue(line.getBytes().length <= 75, line);
    }

    @Test
    void meetingStateMachine() {
        assertEquals(MeetingStatus.SENT, MeetingStatus.DRAFT.transition(MeetingStatus.SENT));
        assertThrows(IllegalStateException.class, () -> MeetingStatus.CANCELLED.transition(MeetingStatus.UPDATED));
    }
}
