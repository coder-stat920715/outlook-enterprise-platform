package com.enterprise.outlook.common.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Liveness == "is this JVM able to make progress". It NEVER touches the DB, Redis, Kafka or the IdP:
 * a downstream outage must not make the orchestrator kill/restart healthy pods (restart death-spiral).
 */
@Component("jvmLiveness")
public class JvmLivenessIndicator implements HealthIndicator {
    @Override
    public Health health() {
        Runtime rt = Runtime.getRuntime();
        double heapUsed = (rt.totalMemory() - rt.freeMemory()) / (double) rt.maxMemory();
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        boolean ok = heapUsed < 0.985 && (deadlocked == null || deadlocked.length == 0);
        return (ok ? Health.up() : Health.down())
                .withDetail("heapUsedRatio", Math.round(heapUsed * 1000) / 1000.0)
                .withDetail("deadlockedThreads", deadlocked == null ? 0 : deadlocked.length)
                .build();
    }
}
