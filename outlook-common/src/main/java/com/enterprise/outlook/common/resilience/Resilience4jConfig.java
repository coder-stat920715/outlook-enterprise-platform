package com.enterprise.outlook.common.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Shared Resilience4j defaults. Retry uses exponential backoff with FULL jitter. */
@Configuration
public class Resilience4jConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50).minimumNumberOfCalls(20)
                .failureRateThreshold(50).slowCallDurationThreshold(Duration.ofSeconds(2)).slowCallRateThreshold(80)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true).build());
    }

    @Bean
    BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(64).maxWaitDuration(Duration.ofMillis(100)).build());
    }

    @Bean
    TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).cancelRunningFuture(true).build());
    }

    @Bean
    RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(500).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ofMillis(50)).build());
    }

    @Bean
    RetryRegistry retryRegistry() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(attempt -> JitterBackoff.fullJitter(attempt - 1, Duration.ofMillis(100), Duration.ofSeconds(5)).toMillis())
                .build());
    }

    @Bean
    ResilientExecutor resilientExecutor(CircuitBreakerRegistry cb, BulkheadRegistry bh, TimeLimiterRegistry tl,
                                        RateLimiterRegistry rl, RetryRegistry rr) {
        return new ResilientExecutor(cb, bh, tl, rl, rr);
    }
}
