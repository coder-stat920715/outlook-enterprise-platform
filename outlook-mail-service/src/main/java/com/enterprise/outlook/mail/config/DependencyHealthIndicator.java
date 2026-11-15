package com.enterprise.outlook.mail.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Informational only (group "dependencies"): reports degradation without ever pulling the pod out of rotation. */
@Component("dependencies")
public class DependencyHealthIndicator implements HealthIndicator {
    private final StringRedisTemplate redis;
    public DependencyHealthIndicator(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Health health() {
        boolean redisOk;
        try { redisOk = redis.getConnectionFactory().getConnection().ping() != null; } catch (Exception e) { redisOk = false; }
        return Health.up().withDetail("redis", redisOk ? "UP" : "DEGRADED").build();
    }
}
