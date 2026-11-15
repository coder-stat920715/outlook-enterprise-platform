package com.enterprise.outlook.common.sharding;

import com.enterprise.outlook.common.resilience.DependencyUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single entry point for shard access: binds the shard, guards it with a per-shard circuit breaker + bulkhead
 * (a sick shard fails fast and never exhausts shared threads), and optionally wraps the work in a transaction.
 */
public class ShardExecutor {
    private final ShardResolver resolver;
    private final TransactionTemplate tx;
    private final CircuitBreakerRegistry breakers;
    private final BulkheadRegistry bulkheads;

    public ShardExecutor(ShardResolver resolver, PlatformTransactionManager txManager,
                         CircuitBreakerRegistry breakers, int maxConcurrentPerShard) {
        this.resolver = resolver;
        this.tx = new TransactionTemplate(txManager);
        this.breakers = breakers;
        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentPerShard * 4).maxWaitDuration(Duration.ofMillis(250)).build());
    }

    public ShardResolver resolver() { return resolver; }

    public <T> T read(UUID tenantId, UUID mailboxId, Supplier<T> work) {
        return onShard(resolver.resolve(tenantId, mailboxId), work, false);
    }

    public <T> T transact(UUID tenantId, UUID mailboxId, Supplier<T> work) {
        return onShard(resolver.resolve(tenantId, mailboxId), work, true);
    }

    public <T> T onShard(String shard, Supplier<T> work, boolean transactional) {
        CircuitBreaker cb = breakers.circuitBreaker(shard);
        Bulkhead bh = bulkheads.bulkhead(shard);
        Supplier<T> bound = () -> {
            try (var ignored = ShardContextHolder.bind(shard)) {
                return transactional ? tx.execute(status -> work.get()) : work.get();
            }
        };
        try {
            return Bulkhead.decorateSupplier(bh, CircuitBreaker.decorateSupplier(cb, bound)).get();
        } catch (CallNotPermittedException | io.github.resilience4j.bulkhead.BulkheadFullException e) {
            throw new DependencyUnavailableException(shard, e);
        }
    }
}
