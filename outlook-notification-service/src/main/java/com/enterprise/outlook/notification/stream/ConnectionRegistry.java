package com.enterprise.outlook.notification.stream;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/** Local fan-out table: mailbox -> open SSE / WebSocket connections on THIS pod. */
@Component
public class ConnectionRegistry {
    public interface Connection {
        /** @return false when the peer is gone and the connection should be dropped. */
        boolean send(String eventId, String json);
        void close();
    }

    private final ConcurrentHashMap<String, Set<Connection>> byMailbox = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    public static String key(UUID tenant, UUID mailbox) { return tenant + ":" + mailbox; }

    public void register(String key, Connection c) { byMailbox.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(c); total.incrementAndGet(); }

    public void unregister(String key, Connection c) {
        byMailbox.computeIfPresent(key, (k, set) -> { if (set.remove(c)) total.decrementAndGet(); return set.isEmpty() ? null : set; });
    }

    public void dispatch(String key, String eventId, String json) {
        Set<Connection> set = byMailbox.get(key);
        if (set == null) return;
        for (Connection c : set) if (!c.send(eventId, json)) unregister(key, c);
    }

    public int connections() { return total.get(); }

    public void closeAll() { byMailbox.values().forEach(s -> s.forEach(Connection::close)); }
}
