package com.enterprise.outlook.gateway.ratelimit;

import com.enterprise.outlook.gateway.auth.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Multi-tier quotas. Per-IP runs BEFORE auth (protects the IdP); per-user and per-tenant (noisy-neighbour isolation) run after. */
public final class RateLimitFilters {
    private RateLimitFilters() {}

    static Mono<Void> tooMany(ServerWebExchange ex, RateLimiterService.Decision d, String scope) {
        ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        ex.getResponse().getHeaders().set("Retry-After", String.valueOf(d.retryAfterSeconds()));
        ex.getResponse().getHeaders().set("X-RateLimit-Scope", scope);
        ex.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(Math.max(0, d.remaining())));
        return ex.getResponse().setComplete();
    }

    @Component
    public static class IpRateLimitFilter implements GlobalFilter, Ordered {
        private final RateLimiterService limiter;
        public IpRateLimitFilter(RateLimiterService limiter) { this.limiter = limiter; }
        @Override public int getOrder() { return -250; }

        @Override
        public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
            if (ex.getRequest().getPath().value().startsWith("/actuator")) return chain.filter(ex);
            String fwd = ex.getRequest().getHeaders().getFirst("X-Forwarded-For");
            String ip = fwd != null ? fwd.split(",")[0].trim()
                    : ex.getRequest().getRemoteAddress() != null ? ex.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
            return limiter.tryConsume(RateLimiterService.Scope.IP, ip)
                    .flatMap(d -> d.allowed() ? chain.filter(ex) : tooMany(ex, d, "ip"));
        }
    }

    @Component
    public static class PrincipalRateLimitFilter implements GlobalFilter, Ordered {
        private final RateLimiterService limiter;
        public PrincipalRateLimitFilter(RateLimiterService limiter) { this.limiter = limiter; }
        @Override public int getOrder() { return -100; }

        @Override
        public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
            Claims c = ex.getAttribute(Claims.ATTR);
            if (c == null) return chain.filter(ex);
            return limiter.tryConsume(RateLimiterService.Scope.USER, c.tenantId() + ":" + c.subject())
                    .flatMap(u -> !u.allowed() ? tooMany(ex, u, "user")
                            : limiter.tryConsume(RateLimiterService.Scope.TENANT, c.tenantId())
                                .flatMap(t -> t.allowed() ? chain.filter(ex) : tooMany(ex, t, "tenant")));
        }
    }
}
