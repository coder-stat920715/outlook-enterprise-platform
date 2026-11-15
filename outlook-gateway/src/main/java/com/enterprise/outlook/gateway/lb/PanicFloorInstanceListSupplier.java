package com.enterprise.outlook.gateway.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active health checking against /actuator/health/readiness with:
 *  - anti-flapping hysteresis (eject after N consecutive failures, restore after M successes);
 *  - MINIMUM-HEALTHY FLOOR / PANIC MODE: if more than (100 - threshold)% of instances are unhealthy at the same time
 *    (default: >25%) ejection is suspended and ALL instances stay routable. A mass health-check failure is far more
 *    likely to be a shared-dependency blip than 25% of the fleet really being dead; ejecting them concentrates load on
 *    the remainder (the cascading overload seen on Nov 12-13, 2026). Overload is then handled by adaptive load shedding.
 */
public class PanicFloorInstanceListSupplier extends DelegatingServiceInstanceListSupplier {
    private static final Logger log = LoggerFactory.getLogger(PanicFloorInstanceListSupplier.class);

    private static final class State { int fails, successes; boolean ejected; }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final WebClient client = WebClient.create();
    private final double panicThresholdPercent;
    private final int ejectAfter, restoreAfter;
    private volatile boolean panic;

    public PanicFloorInstanceListSupplier(ServiceInstanceListSupplier delegate, double panicThresholdPercent,
                                          Duration interval, int ejectAfter, int restoreAfter) {
        super(delegate);
        this.panicThresholdPercent = panicThresholdPercent;
        this.ejectAfter = ejectAfter;
        this.restoreAfter = restoreAfter;
        // jittered probing so a fleet of gateways does not probe in lock-step
        Flux.interval(interval.plusMillis((long) (Math.random() * 1000)), interval).concatMap(t -> probeAll()).onErrorContinue((e, o) -> {}).subscribe();
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(all -> {
            if (all.isEmpty()) return all;
            long healthy = all.stream().filter(i -> !state(i).ejected).count();
            double healthyPct = 100.0 * healthy / all.size();
            boolean nowPanic = healthyPct < panicThresholdPercent;
            if (nowPanic != panic) { panic = nowPanic; log.warn("PANIC MODE {} (healthy={}%)", nowPanic ? "ENGAGED: routing to all instances" : "cleared", (int) healthyPct); }
            return nowPanic ? all : all.stream().filter(i -> !state(i).ejected).toList();
        });
    }

    private State state(ServiceInstance i) { return states.computeIfAbsent(i.getUri().toString(), k -> new State()); }

    private Mono<Void> probeAll() {
        return getDelegate().get().next().flatMapMany(Flux::fromIterable).flatMap(this::probe, 16).then();
    }

    private Mono<Void> probe(ServiceInstance i) {
        return client.get().uri(i.getUri() + "/actuator/health/readiness").retrieve().toBodilessEntity()
                .timeout(Duration.ofSeconds(2)).map(r -> r.getStatusCode().is2xxSuccessful()).onErrorReturn(false)
                .doOnNext(ok -> {
                    State s = state(i);
                    synchronized (s) {
                        if (ok) { s.successes++; s.fails = 0; if (s.ejected && s.successes >= restoreAfter) s.ejected = false; }
                        else { s.fails++; s.successes = 0; if (!s.ejected && s.fails >= ejectAfter) s.ejected = true; }
                    }
                }).then();
    }

    public boolean inPanic() { return panic; }
}
