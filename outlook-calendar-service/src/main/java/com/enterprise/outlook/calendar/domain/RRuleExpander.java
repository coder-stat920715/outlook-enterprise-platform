package com.enterprise.outlook.calendar.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * RFC 5545 RRULE expansion (DAILY / WEEKLY / MONTHLY / YEARLY; INTERVAL, COUNT, UNTIL, BYDAY incl. ordinals, BYMONTHDAY, BYMONTH).
 * Expansion happens on the event's LOCAL wall clock in its IANA zone, then converts to instants, so a "every Monday 09:00
 * America/New_York" meeting stays at 09:00 local across DST transitions (the UTC instant shifts by one hour).
 */
public final class RRuleExpander {
    private RRuleExpander() {}

    public enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    public record DayRule(int ordinal, DayOfWeek day) {}

    public record Rule(Freq freq, int interval, Integer count, Instant until, List<DayRule> byDay,
                       List<Integer> byMonthDay, List<Integer> byMonth) {}

    public record Occurrence(Instant start, Instant end) {}

    private static final int MAX_ITERATIONS = 200_000;
    private static final DateTimeFormatter UTC_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public static Rule parse(String rrule) {
        Freq freq = null; int interval = 1; Integer count = null; Instant until = null;
        List<DayRule> byDay = new ArrayList<>(); List<Integer> byMonthDay = new ArrayList<>(); List<Integer> byMonth = new ArrayList<>();
        for (String part : rrule.replaceFirst("^RRULE:", "").split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String v = kv[1];
            switch (kv[0].toUpperCase()) {
                case "FREQ" -> freq = Freq.valueOf(v.toUpperCase());
                case "INTERVAL" -> interval = Math.max(1, Integer.parseInt(v));
                case "COUNT" -> count = Integer.parseInt(v);
                case "UNTIL" -> until = parseUntil(v);
                case "BYDAY" -> { for (String d : v.split(",")) byDay.add(parseDay(d)); }
                case "BYMONTHDAY" -> { for (String d : v.split(",")) byMonthDay.add(Integer.parseInt(d)); }
                case "BYMONTH" -> { for (String d : v.split(",")) byMonth.add(Integer.parseInt(d)); }
                default -> { /* WKST, BYSETPOS ... ignored */ }
            }
        }
        if (freq == null) throw new IllegalArgumentException("RRULE requires FREQ");
        return new Rule(freq, interval, count, until, byDay, byMonthDay, byMonth);
    }

    private static Instant parseUntil(String v) {
        if (v.length() == 8) return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1);
        return LocalDateTime.parse(v.replace("Z", ""), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC);
    }

    private static DayRule parseDay(String s) {
        String d = s.trim().toUpperCase();
        String code = d.substring(d.length() - 2);
        int ord = d.length() > 2 ? Integer.parseInt(d.substring(0, d.length() - 2).replace("+", "")) : 0;
        DayOfWeek dow = switch (code) {
            case "MO" -> DayOfWeek.MONDAY; case "TU" -> DayOfWeek.TUESDAY; case "WE" -> DayOfWeek.WEDNESDAY; case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY; case "SA" -> DayOfWeek.SATURDAY; case "SU" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("bad BYDAY " + s);
        };
        return new DayRule(ord, dow);
    }

    public static String formatUtc(Instant i) { return UTC_DT.format(i); }

    /** Occurrences overlapping [windowStart, windowEnd), excluding EXDATEs (matched on the occurrence start instant). */
    public static List<Occurrence> expand(Instant start, Instant end, ZoneId zone, Rule rule, Set<Instant> exdates,
                                          Instant windowStart, Instant windowEnd, int maxResults) {
        Duration duration = Duration.between(start, end);
        LocalDateTime base = LocalDateTime.ofInstant(start, zone);
        LocalTime time = base.toLocalTime();
        LocalDate stopDate = LocalDate.ofInstant(windowEnd, zone).plusDays(1);
        List<Occurrence> out = new ArrayList<>();
        int produced = 0;
        for (int k = 0; k < MAX_ITERATIONS; k++) {
            TreeSet<LocalDate> dates = datesForPeriod(base.toLocalDate(), rule, k);
            for (LocalDate d : dates) {
                Instant occStart = LocalDateTime.of(d, time).atZone(zone).toInstant();
                if (occStart.isBefore(start)) continue;                       // before DTSTART
                if (rule.until() != null && occStart.isAfter(rule.until())) return out;
                if (rule.count() != null && produced >= rule.count()) return out;
                produced++;
                if (!occStart.isBefore(windowEnd)) return out;
                Instant occEnd = occStart.plus(duration);
                if (occEnd.isAfter(windowStart) && !exdates.contains(occStart)) {
                    out.add(new Occurrence(occStart, occEnd));
                    if (out.size() >= maxResults) return out;
                }
            }
            if (periodStart(base.toLocalDate(), rule, k).isAfter(stopDate)) break;
        }
        return out;
    }

    private static LocalDate periodStart(LocalDate base, Rule r, int k) {
        return switch (r.freq()) {
            case DAILY -> base.plusDays((long) k * r.interval());
            case WEEKLY -> base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks((long) k * r.interval());
            case MONTHLY -> base.withDayOfMonth(1).plusMonths((long) k * r.interval());
            case YEARLY -> base.withDayOfYear(1).plusYears((long) k * r.interval());
        };
    }

    private static TreeSet<LocalDate> datesForPeriod(LocalDate base, Rule r, int k) {
        TreeSet<LocalDate> out = new TreeSet<>();
        LocalDate ps = periodStart(base, r, k);
        switch (r.freq()) {
            case DAILY -> out.add(ps);
            case WEEKLY -> {
                if (r.byDay().isEmpty()) out.add(ps.plusDays(base.getDayOfWeek().getValue() - 1));
                else for (DayRule d : r.byDay()) out.add(ps.plusDays(d.day().getValue() - 1));
            }
            case MONTHLY -> addMonthDates(out, YearMonth.from(ps), base, r);
            case YEARLY -> {
                List<Integer> months = r.byMonth().isEmpty() ? List.of(base.getMonthValue()) : r.byMonth();
                for (int m : months) addMonthDates(out, YearMonth.of(ps.getYear(), m), base, r);
            }
        }
        return out;
    }

    private static void addMonthDates(Set<LocalDate> out, YearMonth ym, LocalDate base, Rule r) {
        if (!r.byDay().isEmpty()) {
            for (DayRule d : r.byDay()) {
                if (d.ordinal() == 0) {
                    for (LocalDate x = ym.atDay(1); x.getMonth() == ym.getMonth(); x = x.plusDays(1)) if (x.getDayOfWeek() == d.day()) out.add(x);
                } else {
                    LocalDate x = ym.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(d.ordinal(), d.day()));
                    if (x.getMonth() == ym.getMonth()) out.add(x);
                }
            }
        } else {
            List<Integer> days = r.byMonthDay().isEmpty() ? List.of(base.getDayOfMonth()) : r.byMonthDay();
            for (int dom : days) {
                int day = dom > 0 ? dom : ym.lengthOfMonth() + dom + 1;
                if (day >= 1 && day <= ym.lengthOfMonth()) out.add(ym.atDay(day));     // Feb 29 in a non-leap year is skipped
            }
        }
    }
}
