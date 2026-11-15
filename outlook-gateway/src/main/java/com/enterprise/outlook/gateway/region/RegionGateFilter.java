package com.enterprise.outlook.gateway.region;

import com.enterprise.outlook.common.resilience.JitterBackoff;
import com.enterprise.outlook.common.resilience.RequestPriority;
import com.enterprise.outlook.gateway.auth.Claims;
import com.enterprise.outlook.gateway.auth.TenantRoutingCache;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class RegionGateFilter implements GlobalFilter, Ordered {
    private final RegionGateService gates;
    private final TenantRoutingCache routing;

    public RegionGateFilter(RegionGateService gates, TenantRoutingCache routing) { this.gates = gates; this.routing = routing; }

    @Override public int getOrder() { return -150; }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
        Claims claims = ex.getAttribute(Claims.ATTR);
        if (claims == null) return chain.filter(ex);
        var req = ex.getRequest();
        RequestPriority prio = RequestPriority.classify(req.getMethod().name(), req.getPath().value(), req.getHeaders().getFirst("X-Outlook-Priority"));
        return routing.regionFor(claims).flatMap(region -> gates.gate(region).flatMap(g -> {
            boolean admit = switch (g.state()) {
                case "CLOSED" -> prio == RequestPriority.CRITICAL;
                case "DRAINING" -> prio == RequestPriority.INTERACTIVE || prio == RequestPriority.CRITICAL;   // finish in-flight work, no new background load
                case "RAMPING" -> prio == RequestPriority.CRITICAL || ThreadLocalRandom.current().nextInt(100) < g.percent();
                default -> true;
            };
            if (admit) return chain.filter(ex);
            ex.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            ex.getResponse().getHeaders().set("Retry-After", String.valueOf(JitterBackoff.retryAfterSeconds(5, 60)));
            ex.getResponse().getHeaders().set("X-Region-State", region + ":" + g.encode());
            return ex.getResponse().setComplete();
        }));
    }
}
