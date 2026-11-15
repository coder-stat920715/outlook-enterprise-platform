package com.enterprise.outlook.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Decorates a call with Retry(full jitter) > RateLimiter > Bulkhead > CircuitBreaker > TimeLimiter. */
public class ResilientExecutor {
    private final CircuitBreakerRegistry cbr;
    private final BulkheadRegistry bhr;
    private final TimeLimiterRegistry tlr;
    private final RateLimiterRegistry rlr;
    private final RetryRegistry rr;
    private final Executor vt = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientExecutor(CircuitBreakerRegistry cbr, BulkheadRegistry bhr, TimeLimiterRegistry tlr,
                             RateLimiterRegistry rlr, RetryRegistry rr) {
        this.cbr = cbr; this.bhr = bhr; this.tlr = tlr; this.rlr = rlr; this.rr = rr;
    }

    public <T> T execute(String name, Supplier<T> supplier) {
        Supplier<Future<T>> futureSupplier = () -> CompletableFuture.supplyAsync(supplier, vt);
        Callable<T> c = TimeLimiter.decorateFutureSupplier(tlr.timeLimiter(name), futureSupplier);
        c = CircuitBreaker.decorateCallable(cbr.circuitBreaker(name), c);
        c = Bulkhead.decorateCallable(bhr.bulkhead(name), c);
        c = RateLimiter.decorateCallable(rlr.rateLimiter(name), c);
        c = Retry.decorateCallable(rr.retry(name), c);
        try {
            return c.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DependencyUnavailableException(name, e);
        }
    }
}
