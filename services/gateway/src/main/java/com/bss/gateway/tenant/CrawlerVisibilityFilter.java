package com.bss.gateway.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * THE HALF robots.txt CANNOT DO.
 *
 * A `dark` tenant publishes `User-agent: *\nDisallow: /`, and that is a request
 * not to FETCH a page. It is not a request not to LIST one. A search engine that
 * finds a dark tenant's shop URL on someone else's site can index the bare URL
 * — title, breadcrumb and all — precisely because it never fetched the page and
 * so never learned there was nothing to show. Google documents this: to keep a
 * page out of an index you must let the crawler in and tell it noindex, or say
 * noindex in the response headers.
 *
 * So this stamps `X-Robots-Tag: noindex, nofollow` on every response for a host
 * belonging to a dark tenant. It belongs at the gateway for the same reason the
 * security headers do: the browser — and the crawler — only ever talks to the
 * gateway, and the storefront's own nginx knows nothing about tenants. The
 * tenant comes from the Host header, which is also where TenantHostFilter gets
 * it; a client can no more choose its visibility here than it can choose its
 * tenant.
 *
 * It is deliberately NOT scoped to the shop routes. A dark tenant wants none of
 * its surface listed, and a header on an API answer costs nothing.
 */
@Component
public class CrawlerVisibilityFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Robots-Tag";
    public static final String NOINDEX = "noindex, nofollow";

    private final boolean enabled;
    private final TenantHosts tenants;

    public CrawlerVisibilityFilter(
            @Value("${bss.gateway.crawler-visibility-headers:true}") boolean enabled,
            TenantHosts tenants) {
        this.enabled = enabled;
        this.tenants = tenants;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        TenantHosts.Entry tenant = tenants.byHost(exchange.getRequest().getURI().getHost());
        if (tenant == null) {
            // an unmapped host is the default tenant, exactly as TenantHostFilter decides
            tenant = tenants.byId(tenants.getDefaultTenant());
        }
        if (tenant == null || !tenant.isDark()) {
            return chain.filter(exchange);
        }
        // beforeCommit, because a proxied response's headers are written when
        // the downstream answer arrives — after this filter has returned
        exchange.getResponse().beforeCommit(() -> {
            if (!exchange.getResponse().getHeaders().containsKey(HEADER)) {
                exchange.getResponse().getHeaders().set(HEADER, NOINDEX);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // alongside the security headers, after the tenant stamp
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
