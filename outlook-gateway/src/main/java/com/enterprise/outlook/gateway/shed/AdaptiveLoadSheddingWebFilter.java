package com.enterprise.outlook.gateway.shed;

import com.enterprise.outlook.common.resilience.AimdConcurrencyLimiter;
import com.enterprise.outlook.common.resilience.JitterBackoff;
import com.enterprise.outlook.common.resilience.RequestPriority;
import com.enterprise.outlook.common.resilience.VirtualThreadLagMonitor;
import com.enterprise.outlook.gateway.GatewayProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Reactive adaptive concurrency limiter: sheds low-priority work first with 503 + randomised Retry-After. */
@Component
public class AdaptiveLoadSheddingWebFilter implements WebFilter, Ordered {
    private final AimdConcurrencyLimiter limiter;
    private final VirtualThreadLagMonitor lagMonitor;

    public AdaptiveLoadSheddingWebFilter(GatewayProperties props) {
        var s = props.shedding();
        this.limiter = new AimdConcurrencyLimiter(new AimdConcurrencyLimiter.Config(8, s.initialLimit(), s.maxLimit(), 0.2, 1.5, 0.9, s.warmupSeconds() * 1000, 75));
        this.lagMonitor = new VirtualThreadLagMonitor(limiter::onSchedulerLag);
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
        var req = ex.getRequest();
        String path = req.getPath().value();
        // Long-lived SSE/WebSocket streams are admission-controlled by the notification service (ReconnectAdmission);
        // holding a permit for hours would starve the concurrency limit.
        if (path.contains("/notifications") || path.startsWith("/ws/")) return chain.filter(ex);
        RequestPriority p = RequestPriority.classify(req.getMethod().name(), req.getPath().value(), req.getHeaders().getFirst("X-Outlook-Priority"));
        AimdConcurrencyLimiter.Permit permit = limiter.tryAcquire(p);
        if (permit == null) {
            ex.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            ex.getResponse().getHeaders().set("Retry-After", String.valueOf(JitterBackoff.retryAfterSeconds(2, 20)));
            ex.getResponse().getHeaders().set("X-Shed-Reason", "adaptive-concurrency");
            return ex.getResponse().setComplete();
        }
        return chain.filter(ex).doOnSuccess(v -> {
            var st = ex.getResponse().getStatusCode();
            limiter.onComplete(permit, st == null || st.value() < 500);
        }).doOnError(e -> limiter.onComplete(permit, false)).doOnCancel(() -> limiter.onComplete(permit, true));
    }

    public AimdConcurrencyLimiter limiter() { return limiter; }
    @PreDestroy void stop() { lagMonitor.close(); }
}
