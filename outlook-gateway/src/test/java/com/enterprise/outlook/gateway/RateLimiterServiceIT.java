package com.enterprise.outlook.gateway;

import com.enterprise.outlook.gateway.ratelimit.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies distributed token-bucket behaviour on a real Redis. */
@Testcontainers(disabledWithoutDocker = true)
class RateLimiterServiceIT {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @Test
    void tenantQuotaIsEnforcedAcrossInstances() {
        var cfg = new GatewayProperties.RateLimit(true,
                new GatewayProperties.Tier(100, 10), new GatewayProperties.Tier(100, 10), new GatewayProperties.Tier(5, 1));
        String uri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        var a = new RateLimiterService(cfg, uri);      // two "gateway instances" sharing one Redis
        var b = new RateLimiterService(cfg, uri);

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            var svc = i % 2 == 0 ? a : b;
            if (svc.tryConsume(RateLimiterService.Scope.TENANT, "noisy-tenant").block().allowed()) allowed++;
        }
        assertEquals(5, allowed, "burst capacity must be shared across gateway instances");
        assertFalse(a.tryConsume(RateLimiterService.Scope.TENANT, "noisy-tenant").block().allowed());
        assertTrue(a.tryConsume(RateLimiterService.Scope.TENANT, "quiet-tenant").block().allowed(), "other tenants unaffected");
    }
}
