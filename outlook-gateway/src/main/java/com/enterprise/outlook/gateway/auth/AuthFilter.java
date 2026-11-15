package com.enterprise.outlook.gateway.auth;

import com.enterprise.outlook.common.resilience.JitterBackoff;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** JWT/OAuth2 validation + tenant routing headers. Client-supplied identity headers are always overwritten. */
@Component
public class AuthFilter implements GlobalFilter, Ordered {
    private record Outcome(Claims claims, Throwable error) {}

    private final TokenValidator validator;

    public AuthFilter(TokenValidator validator) { this.validator = validator; }

    @Override public int getOrder() { return -200; }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
        String path = ex.getRequest().getPath().value();
        if (path.startsWith("/actuator") || path.startsWith("/fallback") || path.startsWith("/gateway-admin")) return chain.filter(ex);
        String auth = ex.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) return reject(ex, HttpStatus.UNAUTHORIZED, null);

        return validator.validate(auth.substring(7))
                .map(c -> new Outcome(c, null))
                .onErrorResume(e -> Mono.just(new Outcome(null, e)))
                .flatMap(o -> {
                    if (o.claims() == null) {
                        // Bad token => 401. Anything else (IdP down AND nothing cached) => 503 + jittered Retry-After, never a hung handshake.
                        return o.error() instanceof TokenValidator.UnauthorizedException
                                ? reject(ex, HttpStatus.UNAUTHORIZED, null)
                                : reject(ex, HttpStatus.SERVICE_UNAVAILABLE, String.valueOf(JitterBackoff.retryAfterSeconds(2, 15)));
                    }
                    Claims claims = o.claims();
                    ServerHttpRequest mutated = ex.getRequest().mutate().headers(h -> {
                        h.set("X-Tenant-ID", claims.tenantId());
                        h.set("X-User-ID", claims.subject());
                        h.set("X-User-Mailbox-ID", claims.mailboxId());
                        h.set("X-Region", claims.region());
                        h.set("X-TLS-Terminated", "https".equals(h.getFirst("X-Forwarded-Proto")) ? "true" : "false");
                    }).build();
                    ex.getAttributes().put(Claims.ATTR, claims);
                    return chain.filter(ex.mutate().request(mutated).build());
                });
    }

    static Mono<Void> reject(ServerWebExchange ex, HttpStatus status, String retryAfter) {
        ex.getResponse().setStatusCode(status);
        if (retryAfter != null) ex.getResponse().getHeaders().set("Retry-After", retryAfter);
        return ex.getResponse().setComplete();
    }
}
