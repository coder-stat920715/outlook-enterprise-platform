package com.enterprise.outlook.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Ensures every request carries X-Correlation-ID and strips spoofable edge headers. */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {
    public static final String HEADER = "X-Correlation-ID";

    @Override public int getOrder() { return -300; }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
        String id = ex.getRequest().getHeaders().getFirst(HEADER);
        if (id == null || id.isBlank() || id.length() > 64) id = UUID.randomUUID().toString();
        String cid = id;
        var req = ex.getRequest().mutate().headers(h -> {
            h.set(HEADER, cid);
            h.remove("X-Tenant-ID"); h.remove("X-User-ID"); h.remove("X-User-Mailbox-ID"); h.remove("X-Region");
        }).build();
        ex.getResponse().getHeaders().set(HEADER, cid);
        return chain.filter(ex.mutate().request(req).build());
    }
}
