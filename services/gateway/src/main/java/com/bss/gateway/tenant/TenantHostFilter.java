package com.bss.gateway.tenant;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Stamps X-Tenant-Id on every proxied request from the Host header — and
 * strips any inbound copy first, so a client can never choose its own
 * tenant. Downstream services only honor the header for anonymous requests
 * (public catalog, guest carts); authenticated tenancy comes from the
 * verified token issuer.
 */
@Component
public class TenantHostFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Tenant-Id";

    private final TenantHosts tenants;

    public TenantHostFilter(TenantHosts tenants) {
        this.tenants = tenants;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String hostname = exchange.getRequest().getURI().getHost();
        TenantHosts.Entry tenant = tenants.byHost(hostname);
        String tenantId = tenant != null ? tenant.getId() : tenants.getDefaultTenant();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(HEADER);
                    h.set(HEADER, tenantId);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
