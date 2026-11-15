package com.enterprise.outlook.common.resilience;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Exponential backoff with FULL jitter:  sleep = random(0, min(cap, base * 2^attempt)).
 * Used by every internal client and as the reference client-reconnect policy.
 */
public final class JitterBackoff {
    private JitterBackoff() {}

    public static Duration fullJitter(int attempt, Duration base, Duration cap) {
        long capMs = cap.toMillis();
        long exp = base.toMillis() * (1L << Math.min(Math.max(attempt, 0), 20));
        long ceiling = Math.min(capMs, exp <= 0 ? capMs : exp);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(0, ceiling + 1));
    }

    /** Randomised Retry-After seconds for 503 / 429 responses so rejected clients do not return in lock-step. */
    public static int retryAfterSeconds(int minSeconds, int maxSeconds) {
        return ThreadLocalRandom.current().nextInt(minSeconds, maxSeconds + 1);
    }

    public static <T> T execute(int maxAttempts, Duration base, Duration cap, Callable<T> call, Predicate<Throwable> retryable) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new RuntimeException(e);
                if (attempt == maxAttempts - 1 || !retryable.test(e)) break;
                try { Thread.sleep(fullJitter(attempt, base, cap).toMillis()); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
            }
        }
        throw last;
    }

    /** Reference client policy: spread the very first reconnect over a window, then back off with full jitter. */
    public record ClientSyncPolicy(Duration initialSpread, Duration base, Duration cap) {
        public static ClientSyncPolicy defaults() {
            return new ClientSyncPolicy(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(120));
        }
        public Duration firstReconnectDelay() {
            return Duration.ofMillis(ThreadLocalRandom.current().nextLong(0, initialSpread.toMillis() + 1));
        }
        public Duration nextDelay(int attempt, Integer serverRetryAfterSeconds) {
            Duration d = fullJitter(attempt, base, cap);
            if (serverRetryAfterSeconds != null) d = d.plusSeconds(serverRetryAfterSeconds);
            return d;
        }
    }
}
