package com.enterprise.outlook.notification.stream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events push channel. Event id = change_seq: on reconnect the browser sends Last-Event-ID and the server answers
 * with a `resync` event so the client runs one delta query (the delta log, not the push channel, is the source of truth).
 */
@RestController
public class SseController {
    private final ConnectionRegistry registry;
    private final ReconnectAdmission admission;
    private final Set<SseConnection> open = ConcurrentHashMap.newKeySet();

    public SseController(ConnectionRegistry registry, ReconnectAdmission admission) { this.registry = registry; this.admission = admission; }

    final class SseConnection implements ConnectionRegistry.Connection {
        final SseEmitter emitter = new SseEmitter(0L);
        final String key;
        SseConnection(String key) { this.key = key; }

        @Override public synchronized boolean send(String eventId, String json) {
            try { emitter.send(SseEmitter.event().id(eventId).name("change").data(json)); return true; } catch (IOException | IllegalStateException e) { return false; }
        }
        synchronized boolean heartbeat() {
            try { emitter.send(SseEmitter.event().comment("hb")); return true; } catch (IOException | IllegalStateException e) { return false; }
        }
        @Override public void close() { emitter.complete(); }
    }

    @GetMapping(path = "/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object stream(@RequestHeader("X-Tenant-ID") UUID tenant, @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor,
                         @RequestParam UUID mailboxId, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) throws IOException {
        if (actor != null && !actor.equals(mailboxId)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var decision = admission.admit();
        if (!decision.admitted()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds())).build();
        }
        String key = ConnectionRegistry.key(tenant, mailboxId);
        SseConnection c = new SseConnection(key);
        c.emitter.onCompletion(() -> { registry.unregister(key, c); open.remove(c); });
        c.emitter.onTimeout(c.emitter::complete);
        c.emitter.onError(e -> { registry.unregister(key, c); open.remove(c); });
        registry.register(key, c);
        open.add(c);
        c.emitter.send(SseEmitter.event().reconnectTime(admission.clientRetryMillis()).name("ready").data("{\"resync\":" + (lastEventId != null) + "}"));
        return c.emitter;
    }

    @Scheduled(fixedDelay = 20_000)
    void heartbeats() {
        for (SseConnection c : open) if (!c.heartbeat()) { registry.unregister(c.key, c); open.remove(c); }
    }
}
