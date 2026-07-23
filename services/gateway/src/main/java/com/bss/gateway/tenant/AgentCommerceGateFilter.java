package com.bss.gateway.tenant;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * The per-tenant agentic-commerce switch, enforced at the one choke point
 * every agent call must cross. A tenant chooses how much of itself AI
 * shopping agents may see:
 *
 *   off       — the tenant is dark: every /acp/* path 404s (not 403 — a
 *               closed door should not confirm there is a room behind it)
 *   discovery — the product feed answers (findable, comparable), but agent
 *               checkout is refused with an honest 403: humans keep the funnel
 *   full      — feed and delegated checkout both open
 *
 * The flag lives in tenants.yml like every other tenant capability and
 * live-refreshes — flipping a tenant's exposure is a config edit, not a
 * deploy. Downstream services check it again (defense in depth), but a
 * tenant set to 'off' is unreachable even if a downstream is misconfigured.
 */
@Component
public class AgentCommerceGateFilter implements GlobalFilter, Ordered {

    private static final String ACP_PREFIX = "/acp/";
    private static final String FEED_PATH = "/acp/product_feed";

    private final TenantHosts tenants;

    public AgentCommerceGateFilter(TenantHosts tenants) {
        this.tenants = tenants;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(ACP_PREFIX)) {
            return chain.filter(exchange);
        }
        // TenantHostFilter (highest precedence) has already stamped the header.
        String tenantId = exchange.getRequest().getHeaders().getFirst(TenantHostFilter.HEADER);
        TenantHosts.Entry tenant = tenantId == null ? null : tenants.byId(tenantId);
        String mode = tenant == null ? "off"
                : tenant.getAgentCommerce() == null ? "off" : tenant.getAgentCommerce();
        if ("full".equals(mode)) {
            return chain.filter(exchange);
        }
        if ("discovery".equals(mode)) {
            boolean feedRead = FEED_PATH.equals(path)
                    && HttpMethod.GET.equals(exchange.getRequest().getMethod());
            if (feedRead) {
                return chain.filter(exchange);
            }
            return refuse(exchange, HttpStatus.FORBIDDEN,
                    "agent_checkout_disabled",
                    "this operator lists its catalog to agents but keeps checkout human");
        }
        return refuse(exchange, HttpStatus.NOT_FOUND, "not_found", "no agentic commerce surface here");
    }

    private Mono<Void> refuse(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"type\":\"invalid_request\",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // After TenantHostFilter (HIGHEST_PRECEDENCE) so the header is
        // stamped; before the rate limiter — a dark tenant costs nothing.
        return -60;
    }
}
