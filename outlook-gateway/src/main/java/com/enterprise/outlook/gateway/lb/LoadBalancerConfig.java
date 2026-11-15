package com.enterprise.outlook.gateway.lb;

import com.enterprise.outlook.gateway.GatewayProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Static service registry (compose / k8s DNS) + panic-floor aware supplier for mail-service, calendar-service, notification-service. */
@Configuration
@LoadBalancerClients(defaultConfiguration = LoadBalancerConfig.PanicFloorConfiguration.class)
public class LoadBalancerConfig {

    public static class PanicFloorConfiguration {
        @Bean
        ServiceInstanceListSupplier panicFloorSupplier(org.springframework.core.env.Environment env, GatewayProperties props) {
            return new ServiceInstanceListSupplier() {
                private PanicFloorInstanceListSupplier delegate;
                private synchronized PanicFloorInstanceListSupplier d() {
                    if (delegate == null) {
                        String id = env.getProperty("loadbalancer.client.name", "mail-service");
                        delegate = new PanicFloorInstanceListSupplier(new StaticSupplier(id, upstreamsFor(id, props)),
                                props.panic().healthyPanicThresholdPercent(), Duration.ofMillis(props.panic().probeIntervalMillis()),
                                props.panic().ejectAfterFailures(), props.panic().restoreAfterSuccesses());
                    }
                    return delegate;
                }
                @Override public String getServiceId() { return d().getServiceId(); }
                @Override public Flux<List<ServiceInstance>> get() { return d().get(); }
            };
        }
    }

    static List<String> upstreamsFor(String serviceId, GatewayProperties props) {
        return switch (serviceId) {
            case "calendar-service" -> props.panic().calendarUpstreams();
            case "notification-service" -> props.panic().notificationUpstreams();
            default -> props.panic().mailUpstreams();
        };
    }

    static final class StaticSupplier implements ServiceInstanceListSupplier {
        private final String id;
        private final List<ServiceInstance> instances;
        StaticSupplier(String id, List<String> uris) {
            this.id = id;
            this.instances = uris.stream().map(u -> {
                URI uri = URI.create(u);
                return (ServiceInstance) new DefaultServiceInstance(id + "@" + uri.getHost() + ":" + uri.getPort(), id, uri.getHost(), uri.getPort(), "https".equals(uri.getScheme()));
            }).toList();
        }
        @Override public String getServiceId() { return id; }
        @Override public Flux<List<ServiceInstance>> get() { return Flux.just(instances); }
    }
}
