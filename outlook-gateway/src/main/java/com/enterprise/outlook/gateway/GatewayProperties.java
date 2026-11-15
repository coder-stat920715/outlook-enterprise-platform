package com.enterprise.outlook.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "outlook.gateway")
public record GatewayProperties(
        @DefaultValue Auth auth,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Panic panic,
        @DefaultValue Shedding shedding,
        @DefaultValue("change-me") String adminKey,
        @DefaultValue("redis://localhost:6379") String redisUri) {

    public record Auth(@DefaultValue("introspect") String mode,
                       @DefaultValue("http://localhost:9099/introspect") String introspectionUrl,
                       @DefaultValue("") String routingUrl,
                       @DefaultValue("dev-hmac-secret-change-me-please-32b") String hmacSecret,
                       @DefaultValue("60") long freshSeconds,
                       @DefaultValue("300") long swrSeconds,
                       @DefaultValue("3600") long staleIfErrorSeconds,
                       @DefaultValue("1500") long upstreamTimeoutMillis) {}

    public record Tier(@DefaultValue("100") long capacity, @DefaultValue("20") long refillPerSecond) {}

    public record RateLimit(@DefaultValue("true") boolean enabled,
                            @DefaultValue Tier ip, @DefaultValue Tier user, @DefaultValue Tier tenant) {}

    public record Panic(@DefaultValue("75") double healthyPanicThresholdPercent,
                        @DefaultValue("5000") long probeIntervalMillis,
                        @DefaultValue("3") int ejectAfterFailures,
                        @DefaultValue("2") int restoreAfterSuccesses,
                        @DefaultValue List<String> mailUpstreams,
                        @DefaultValue List<String> calendarUpstreams,
                        @DefaultValue List<String> notificationUpstreams) {}

    public record Shedding(@DefaultValue("2048") int maxLimit, @DefaultValue("128") int initialLimit,
                           @DefaultValue("60") long warmupSeconds) {}
}
