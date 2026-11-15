package com.enterprise.outlook.gateway.ratelimit;

import com.enterprise.outlook.gateway.GatewayProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Distributed token-bucket rate limiting (Bucket4j on Redis). Fails OPEN if Redis is unavailable. */
@Service
public class RateLimiterService {
    public enum Scope { IP, USER, TENANT }

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        static Decision open() { return new Decision(true, -1, 0); }
    }

    private final GatewayProperties.RateLimit cfg;
    private final LettuceBasedProxyManager<byte[]> proxyManager;

    @Autowired
    public RateLimiterService(GatewayProperties props) {
        this.cfg = props.rateLimit();
        RedisClient client = RedisClient.create(props.redisUri());
        this.proxyManager = LettuceBasedProxyManager.builderFor(client)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
                .build();
    }

    /** Package-visible constructor for tests. */
    public RateLimiterService(GatewayProperties.RateLimit cfg, String redisUri) {
        this.cfg = cfg;
        this.proxyManager = LettuceBasedProxyManager.builderFor(RedisClient.create(redisUri))
                .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
                .build();
    }

    public Mono<Decision> tryConsume(Scope scope, String id) {
        if (!cfg.enabled()) return Mono.just(Decision.open());
        GatewayProperties.Tier tier = switch (scope) { case IP -> cfg.ip(); case USER -> cfg.user(); case TENANT -> cfg.tenant(); };
        BucketConfiguration bc = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(tier.capacity()).refillGreedy(tier.refillPerSecond(), Duration.ofSeconds(1)).build())
                .build();
        byte[] key = ("rl:" + scope.name().toLowerCase() + ":" + id).getBytes(StandardCharsets.UTF_8);
        return Mono.fromFuture(() -> proxyManager.asAsync().builder()
                        .build(key, () -> CompletableFuture.completedFuture(bc))
                        .tryConsumeAndReturnRemaining(1))
                .map(RateLimiterService::toDecision)
                .timeout(Duration.ofMillis(100))
                .onErrorReturn(Decision.open());
    }

    private static Decision toDecision(ConsumptionProbe p) {
        long retry = p.isConsumed() ? 0 : Math.max(1, (long) Math.ceil(p.getNanosToWaitForRefill() / 1e9));
        return new Decision(p.isConsumed(), p.getRemainingTokens(), retry);
    }
}
