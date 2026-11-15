package com.enterprise.outlook.common.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness: stays OUT_OF_SERVICE for a short warm-up hold (cold caches / pools) and then reports UP.
 * Dependency problems are surfaced as details only; they do NOT eject the pod (a pod that is
 * "up but degraded" sheds load itself, which is better than the whole fleet leaving rotation at once).
 */
@Component("warmupReadiness")
public class WarmupReadinessIndicator implements HealthIndicator {
    private final long startedAt = System.currentTimeMillis();
    private final long holdMillis;

    public WarmupReadinessIndicator(@Value("${outlook.readiness.warmup-hold-seconds:5}") long holdSeconds) {
        this.holdMillis = holdSeconds * 1000;
    }

    @Override
    public Health health() {
        long elapsed = System.currentTimeMillis() - startedAt;
        return (elapsed >= holdMillis ? Health.up() : Health.outOfService()).withDetail("uptimeMillis", elapsed).build();
    }
}
