package com.enterprise.outlook.notification.stream;

import com.enterprise.outlook.common.resilience.JitterBackoff;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Connection admission control: caps NEW connections per second and the total open connections of this pod.
 * After a restart / regional restore, millions of clients reconnect at once; excess are told to retry with a
 * jittered Retry-After instead of stampeding the pod (and the downstream resync path).
 */
@Component
public class ReconnectAdmission {
    public record Decision(boolean admitted, int retryAfterSeconds) {}

    private final Bucket newConnections;
    private final int maxConnections;
    private final ConnectionRegistry registry;

    public ReconnectAdmission(ConnectionRegistry registry,
                              @Value("${outlook.notification.max-new-connections-per-second:200}") int perSecond,
                              @Value("${outlook.notification.max-connections:50000}") int maxConnections) {
        this.registry = registry;
        this.maxConnections = maxConnections;
        this.newConnections = Bucket.builder().addLimit(Bandwidth.builder().capacity(perSecond * 2L).refillGreedy(perSecond, Duration.ofSeconds(1)).build()).build();
    }

    public Decision admit() {
        if (registry.connections() >= maxConnections || !newConnections.tryConsume(1))
            return new Decision(false, JitterBackoff.retryAfterSeconds(3, 45));
        return new Decision(true, 0);
    }

    /** SSE `retry:` hint sent to the client – a randomised reconnect delay so one pod restart does not re-synchronise clients. */
    public long clientRetryMillis() { return JitterBackoff.fullJitter(3, Duration.ofSeconds(1), Duration.ofSeconds(30)).toMillis() + 1000; }
}
