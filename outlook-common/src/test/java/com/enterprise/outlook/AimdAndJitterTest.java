package com.enterprise.outlook;

import com.enterprise.outlook.common.model.DeliveryState;
import com.enterprise.outlook.common.resilience.AimdConcurrencyLimiter;
import com.enterprise.outlook.common.resilience.JitterBackoff;
import com.enterprise.outlook.common.resilience.RequestPriority;
import com.enterprise.outlook.common.sharding.ShardResolver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AimdAndJitterTest {

    @Test
    void backgroundIsShedBeforeInteractive() {
        long[] now = {120_000};
        var l = new AimdConcurrencyLimiter(new AimdConcurrencyLimiter.Config(4, 64, 1024, 0.2, 1.5, 0.9, 0, 75), () -> 0L, () -> now[0]);
        int bg = 0; while (l.tryAcquire(RequestPriority.BACKGROUND) != null) bg++;
        assertEquals(32, bg);
        assertNotNull(l.tryAcquire(RequestPriority.INTERACTIVE));
        assertNotNull(l.tryAcquire(RequestPriority.CRITICAL));
    }

    @Test
    void warmupCeilingStartsAtFivePercent() {
        long[] now = {0};
        var l = new AimdConcurrencyLimiter(new AimdConcurrencyLimiter.Config(4, 2000, 1000, 0.2, 1.5, 0.9, 60_000, 75), () -> 0L, () -> now[0]);
        assertEquals(50.0, l.effectiveLimit(), 1.0);
        now[0] = 60_000;
        assertEquals(1000.0, l.effectiveLimit(), 1.0);
    }

    @Test
    void fullJitterStaysWithinCap() {
        for (int i = 0; i < 1000; i++) {
            long ms = JitterBackoff.fullJitter(i % 25, Duration.ofMillis(100), Duration.ofSeconds(5)).toMillis();
            assertTrue(ms >= 0 && ms <= 5000);
        }
    }

    @Test
    void shardingIsStableAndBalanced() {
        var r = new ShardResolver(16, 256);
        UUID t = UUID.randomUUID();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 16000; i++) {
            UUID m = UUID.randomUUID();
            String s = r.resolve(t, m);
            assertEquals(s, r.resolve(t, m));
            counts.merge(s, 1, Integer::sum);
        }
        assertEquals(16, counts.size());
        counts.values().forEach(c -> assertTrue(c > 500 && c < 1600, "skewed shard: " + c));
    }

    @Test
    void deliveryStateMachine() {
        assertTrue(DeliveryState.QUEUED.canTransitionTo(DeliveryState.SENDING));
        assertTrue(DeliveryState.DEFERRED_RETRY.canTransitionTo(DeliveryState.DLQ));
        assertFalse(DeliveryState.DELIVERED.canTransitionTo(DeliveryState.SENDING));
        assertThrows(IllegalStateException.class, () -> DeliveryState.DRAFT.transition(DeliveryState.DELIVERED));
    }
}
