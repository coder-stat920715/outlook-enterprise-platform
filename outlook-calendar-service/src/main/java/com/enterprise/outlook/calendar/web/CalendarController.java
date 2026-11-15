package com.enterprise.outlook.calendar.web;

import com.enterprise.outlook.calendar.domain.Models.*;
import com.enterprise.outlook.calendar.service.CalendarService;
import com.enterprise.outlook.calendar.service.FreeBusyService;
import com.enterprise.outlook.calendar.service.RoomBookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class CalendarController {
    private final CalendarService calendar;
    private final FreeBusyService freeBusy;
    private final RoomBookingService rooms;

    public CalendarController(CalendarService calendar, FreeBusyService freeBusy, RoomBookingService rooms) {
        this.calendar = calendar; this.freeBusy = freeBusy; this.rooms = rooms;
    }

    /** Only the mailbox owner (or a delegate resolved upstream) may mutate; the gateway-injected actor must match the mailbox. */
    private void owner(UUID mailbox, UUID actor) {
        if (actor != null && !actor.equals(mailbox)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "calendar delegation requires the owner");
    }

    @PostMapping("/mailboxes/{mailboxId}/events")
    public ResponseEntity<CalendarEvent> create(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                                @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody CreateEventRequest r) {
        owner(mailboxId, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(calendar.create(tenant, mailboxId, r));
    }

    @GetMapping("/mailboxes/{mailboxId}/events")
    public List<Occurrence> window(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                   @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor,
                                   @RequestParam Instant from, @RequestParam Instant to) {
        owner(mailboxId, actor);
        return calendar.window(tenant, mailboxId, from, to);
    }

    @GetMapping("/mailboxes/{mailboxId}/events/{eventId}")
    public CalendarEvent get(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID eventId,
                             @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        owner(mailboxId, actor);
        return calendar.get(tenant, mailboxId, eventId);
    }

    @PutMapping("/mailboxes/{mailboxId}/events/{eventId}")
    public CalendarEvent update(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID eventId,
                                @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody UpdateEventRequest r) {
        owner(mailboxId, actor);
        return calendar.update(tenant, mailboxId, eventId, r);
    }

    @PostMapping("/mailboxes/{mailboxId}/events/{eventId}/cancel")
    public ResponseEntity<Void> cancel(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID eventId,
                                       @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        owner(mailboxId, actor);
        calendar.cancel(tenant, mailboxId, eventId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mailboxes/{mailboxId}/events/{eventId}/respond")
    public ResponseEntity<Void> respond(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID eventId,
                                        @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody RespondRequest r) {
        owner(mailboxId, actor);
        calendar.respond(tenant, mailboxId, eventId, r.response());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/freebusy")
    public FreeBusyResponse freeBusy(@RequestHeader("X-Tenant-ID") UUID tenant, @RequestBody FreeBusyRequest r) { return freeBusy.query(tenant, r); }

    // ---- resources / rooms --------------------------------------------------------------------------------------------------
    @PostMapping("/resources")
    public Map<String, UUID> createResource(@RequestHeader("X-Tenant-ID") UUID tenant, @RequestBody ResourceRequest r) {
        return Map.of("resourceId", rooms.createResource(tenant, r));
    }

    @PostMapping("/resources/{resourceId}/book")
    public ResponseEntity<Booking> book(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID resourceId, @RequestBody BookRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rooms.book(tenant, resourceId, r));
    }

    @PutMapping("/resources/{resourceId}/bookings/{bookingId}")
    public Booking reschedule(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID resourceId, @PathVariable UUID bookingId,
                              @RequestBody RescheduleRequest r) {
        return rooms.reschedule(tenant, resourceId, bookingId, r);
    }

    @DeleteMapping("/resources/{resourceId}/bookings/{bookingId}")
    public ResponseEntity<Void> cancelBooking(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID resourceId, @PathVariable UUID bookingId) {
        rooms.cancel(tenant, resourceId, bookingId);
        return ResponseEntity.noContent().build();
    }
}
