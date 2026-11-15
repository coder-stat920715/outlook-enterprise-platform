package com.enterprise.outlook.gateway.region;

import com.enterprise.outlook.common.model.Region;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Per-region traffic gate stored in Redis (key gate:region:{REGION}) so operators can drain / restore
 * APAC, EMEA, AMER independently and incrementally. States: OPEN | DRAINING | CLOSED | RAMPING:{percent}.
 */
@Service
public class RegionGateService {
    public record Gate(String state, int percent) {
        public static Gate open() { return new Gate("OPEN", 100); }
        public static Gate parse(String raw) {
            if (raw == null || raw.isBlank()) return open();
            if (raw.startsWith("RAMPING:")) {
                try { return new Gate("RAMPING", Math.max(0, Math.min(100, Integer.parseInt(raw.substring(8))))); }
                catch (NumberFormatException e) { return open(); }
            }
            return switch (raw) { case "DRAINING" -> new Gate("DRAINING", 0); case "CLOSED" -> new Gate("CLOSED", 0); default -> open(); };
        }
        public String encode() { return "RAMPING".equals(state) ? "RAMPING:" + percent : state; }
    }

    private final ReactiveStringRedisTemplate redis;
    private final Cache<Region, Gate> local = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(2)).build();

    public RegionGateService(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    private static String key(Region r) { return "gate:region:" + r.name(); }

    /** Fail-open: if Redis is unreachable the gate is OPEN (a broken control plane must not black-hole traffic). */
    public Mono<Gate> gate(Region region) {
        Gate cached = local.getIfPresent(region);
        if (cached != null) return Mono.just(cached);
        return redis.opsForValue().get(key(region)).map(Gate::parse).defaultIfEmpty(Gate.open())
                .timeout(Duration.ofMillis(100)).onErrorReturn(Gate.open())
                .doOnNext(g -> local.put(region, g));
    }

    public Mono<Gate> set(Region region, Gate gate) {
        local.invalidate(region);
        return redis.opsForValue().set(key(region), gate.encode()).thenReturn(gate);
    }

    /** Phased restore: RAMPING:p1 -> RAMPING:p2 ... -> OPEN, one step per interval. */
    public Flux<Gate> ramp(Region region, int[] steps, Duration interval) {
        return Flux.interval(Duration.ZERO, interval).take(steps.length + 1)
                .concatMap(i -> i < steps.length ? set(region, new Gate("RAMPING", steps[(int) (long) i])) : set(region, Gate.open()));
    }

    public Mono<Map<Region, Gate>> all() {
        return Flux.fromArray(Region.values()).concatMap(r -> gate(r).map(g -> Map.entry(r, g)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
