package com.enterprise.outlook.calendar.domain;

import com.enterprise.outlook.calendar.domain.Models.CalendarEvent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** RFC 5545 iCalendar (METHOD:REQUEST | CANCEL | REPLY) with TZID-local DTSTART so recurring invites survive DST. */
public final class IcsBuilder {
    private IcsBuilder() {}
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public static String build(String method, CalendarEvent e, String replyFromEmail, Models.Response replyResponse) {
        ZoneId zone = ZoneId.of(e.timeZone());
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR"); line(sb, "VERSION:2.0"); line(sb, "PRODID:-//Enterprise Outlook//Calendar 1.0//EN");
        line(sb, "CALSCALE:GREGORIAN"); line(sb, "METHOD:" + method);
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + e.icsUid());
        line(sb, "DTSTAMP:" + RRuleExpander.formatUtc(java.time.Instant.now()));
        line(sb, "DTSTART;TZID=" + e.timeZone() + ":" + LOCAL.format(LocalDateTime.ofInstant(e.startAt(), zone)));
        line(sb, "DTEND;TZID=" + e.timeZone() + ":" + LOCAL.format(LocalDateTime.ofInstant(e.endAt(), zone)));
        if (e.recurring()) line(sb, "RRULE:" + e.rrule().replaceFirst("^RRULE:", ""));
        for (var ex : e.exdates()) line(sb, "EXDATE;TZID=" + e.timeZone() + ":" + LOCAL.format(LocalDateTime.ofInstant(ex, zone)));
        line(sb, "SEQUENCE:" + e.sequenceNo());
        line(sb, "SUMMARY:" + escape(e.title()));
        if (e.location() != null) line(sb, "LOCATION:" + escape(e.location()));
        line(sb, "ORGANIZER:mailto:" + e.organizerEmail());
        if ("REPLY".equals(method)) {
            line(sb, "ATTENDEE;PARTSTAT=" + (replyResponse == null ? "NEEDS-ACTION" : replyResponse.name().replace('_', '-')) + ":mailto:" + replyFromEmail);
        } else {
            for (var a : e.attendees())
                line(sb, "ATTENDEE;CN=" + escape(a.name() == null ? a.email() : a.name()) + ";PARTSTAT=" + a.response().name().replace('_', '-')
                        + ";RSVP=TRUE:mailto:" + a.email());
        }
        line(sb, "STATUS:" + ("CANCEL".equals(method) ? "CANCELLED" : "CONFIRMED"));
        line(sb, "END:VEVENT"); line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private static String escape(String s) { return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n"); }

    /** Content lines are folded at 75 octets with CRLF + space. */
    private static void line(StringBuilder out, String content) {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        if (b.length <= 75) { out.append(content).append("\r\n"); return; }
        int i = 0; boolean first = true;
        while (i < content.length()) {
            int limit = first ? 75 : 74; int bytes = 0; int j = i;
            while (j < content.length()) {
                int cl = String.valueOf(content.charAt(j)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + cl > limit) break;
                bytes += cl; j++;
            }
            out.append(first ? "" : " ").append(content, i, j).append("\r\n");
            i = j; first = false;
        }
    }
}
