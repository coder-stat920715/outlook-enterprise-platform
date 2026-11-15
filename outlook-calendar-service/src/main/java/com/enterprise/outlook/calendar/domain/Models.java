package com.enterprise.outlook.calendar.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Models {
    private Models() {}

    /** Meeting invite lifecycle: DRAFT -> SENT -> UPDATED (repeatable) -> CANCELLED. */
    public enum MeetingStatus {
        DRAFT, SENT, UPDATED, CANCELLED;
        public Set<MeetingStatus> allowedNext() {
            return switch (this) {
                case DRAFT -> EnumSet.of(SENT, CANCELLED);
                case SENT, UPDATED -> EnumSet.of(UPDATED, CANCELLED);
                case CANCELLED -> EnumSet.noneOf(MeetingStatus.class);
            };
        }
        public MeetingStatus transition(MeetingStatus next) {
            if (!allowedNext().contains(next)) throw new IllegalStateException("Illegal meeting transition " + this + " -> " + next);
            return next;
        }
    }

    public enum Response { NEEDS_ACTION, ACCEPTED, TENTATIVE, DECLINED }

    public record Attendee(String email, String name, Response response) {}

    public record CalendarEvent(UUID eventId, String icsUid, String title, String location, Instant startAt, Instant endAt,
                                String timeZone, boolean allDay, String rrule, List<Instant> exdates, Instant seriesUntil,
                                String organizerEmail, List<Attendee> attendees, MeetingStatus meetingStatus, int sequenceNo,
                                String showAs, long version) {
        public boolean recurring() { return rrule != null && !rrule.isBlank(); }
    }

    public record Occurrence(UUID eventId, String title, Instant start, Instant end, boolean recurring, String showAs, String status) {}

    // ---- API payloads ------------------------------------------------------------------------------------------------
    public record CreateEventRequest(String title, String location, Instant startAt, Instant endAt, String timeZone, boolean allDay,
                                     String rrule, List<Instant> exdates, List<Attendee> attendees, boolean sendInvites, String showAs) {}

    public record UpdateEventRequest(String title, String location, Instant startAt, Instant endAt, String timeZone, String rrule,
                                     List<Instant> exdates, List<Attendee> attendees, long version) {}

    public record RespondRequest(Response response) {}

    public record FreeBusyTarget(UUID tenantId, UUID mailboxId) {}

    public record FreeBusyRequest(List<FreeBusyTarget> mailboxes, Instant start, Instant end, int durationMinutes) {}

    public record Interval(Instant start, Instant end) {}

    public record FreeBusyResponse(java.util.Map<UUID, List<Interval>> busy, List<Interval> commonFreeSlots) {}

    public record ResourceRequest(String name, String kind, int capacity, String location) {}

    public record BookRequest(Instant startAt, Instant endAt, String organizerEmail, String title) {}

    public record Booking(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt, String organizerEmail, String title, long version) {}

    public record RescheduleRequest(Instant startAt, Instant endAt, long version) {}
}
