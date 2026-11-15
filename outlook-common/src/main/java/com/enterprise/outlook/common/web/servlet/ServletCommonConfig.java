package com.enterprise.outlook.common.web.servlet;

import com.enterprise.outlook.common.resilience.AimdConcurrencyLimiter;
import com.enterprise.outlook.common.resilience.VirtualThreadLagMonitor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServletCommonConfig {

    @Bean
    AimdConcurrencyLimiter aimdConcurrencyLimiter(@Value("${outlook.load-shedding.max-limit:1024}") int max,
                                                  @Value("${outlook.load-shedding.initial-limit:64}") int initial,
                                                  @Value("${outlook.load-shedding.warmup-seconds:60}") long warmup,
                                                  MeterRegistry meters) {
        var l = new AimdConcurrencyLimiter(new AimdConcurrencyLimiter.Config(4, initial, max, 0.2, 1.5, 0.9, warmup * 1000, 75.0));
        Gauge.builder("outlook.limiter.limit", l, AimdConcurrencyLimiter::effectiveLimit).register(meters);
        Gauge.builder("outlook.limiter.inflight", l, x -> x.inFlight()).register(meters);
        Gauge.builder("outlook.limiter.rejected", l, x -> x.rejectedCount()).register(meters);
        return l;
    }

    @Bean(destroyMethod = "close")
    VirtualThreadLagMonitor virtualThreadLagMonitor(AimdConcurrencyLimiter limiter) {
        return new VirtualThreadLagMonitor(limiter::onSchedulerLag);
    }

    @Bean
    FilterRegistrationBean<AdaptiveLoadSheddingFilter> loadSheddingFilter(AimdConcurrencyLimiter limiter) {
        var reg = new FilterRegistrationBean<>(new AdaptiveLoadSheddingFilter(limiter));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
