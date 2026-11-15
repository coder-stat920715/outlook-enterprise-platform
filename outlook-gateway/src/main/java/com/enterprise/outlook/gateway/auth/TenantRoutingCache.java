package com.enterprise.outlook.gateway.auth;

import com.enterprise.outlook.common.model.Region;
import com.enterprise.outlook.common.resilience.StaleWhileRevalidateCache;
import com.enterprise.outlook.common.util.Json;
import com.enterprise.outlook.gateway.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Tenant -> home region lookup from the routing control plane, through the same SWR cache machinery.
 * If the control plane is unavailable and nothing is cached, we fall back to the region claim in the token.
 */
@Component
public class TenantRoutingCache {
    public record TenantRoute(String tenantId, String region) {}

    private final StaleWhileRevalidateCache<TenantRoute> cache;
    private final boolean enabled;

    public TenantRoutingCache(GatewayProperties props, ReactiveStringRedisTemplate redis) {
        var url = props.auth().routingUrl();
        this.enabled = url != null && !url.isBlank();
        var client = WebClient.create();
        var l2 = new StaleWhileRevalidateCache.L2Store() {
            @Override public CompletableFuture<String> get(String k) { return redis.opsForValue().get("route:" + k).onErrorResume(e -> Mono.empty()).toFuture(); }
            @Override public CompletableFuture<Void> put(String k, String v, Duration ttl) { return redis.opsForValue().set("route:" + k, v, ttl).onErrorReturn(false).then().toFuture(); }
        };
        this.cache = new StaleWhileRevalidateCache<>("tenant-routing",
                new StaleWhileRevalidateCache.Policy(Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(6), 50_000, Duration.ofMillis(800)),
                l2, tenant -> client.get().uri(url + "/tenants/" + tenant).retrieve().bodyToMono(String.class)
                        .map(b -> { JsonNode n = Json.read(b, JsonNode.class); return new TenantRoute(tenant, n.path("region").asText("AMER")); }).toFuture(),
                Json::write, s -> Json.read(s, TenantRoute.class), null);
    }

    public Mono<Region> regionFor(Claims claims) {
        Region claimRegion = Region.parseOrDefault(claims.region(), Region.AMER);
        if (!enabled) return Mono.just(claimRegion);
        return Mono.fromFuture(() -> cache.get(claims.tenantId())).map(r -> Region.parseOrDefault(r.region(), claimRegion)).onErrorReturn(claimRegion);
    }
}
