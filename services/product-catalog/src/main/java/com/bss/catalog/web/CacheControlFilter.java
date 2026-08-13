package com.bss.catalog.web;

import com.bss.catalog.api.ApiConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The caching contract for the public price list. The catalog browse GETs are
 * identical for every ANONYMOUS visitor of a tenant, so they may be cached at the
 * edge (a campaign-day surge is thousands of prospects loading the same offers). We
 * mark them cacheable and say the cache MUST vary by tenant, so genalpha's catalogue
 * is never served to nova. A request that carries a token (a logged-in visitor) is
 * marked no-store — a personalised or issuer-resolved response must never populate
 * the shared anonymous cache. The gateway's LocalResponseCache honours this.
 */
@Component
public class CacheControlFilter extends OncePerRequestFilter {

    private final long ttlSeconds;
    private final boolean enabled;

    public CacheControlFilter(
            @Value("${bss.catalog.browse-cache.ttl-seconds:60}") long ttlSeconds,
            @Value("${bss.catalog.browse-cache.enabled:true}") boolean enabled) {
        this.ttlSeconds = ttlSeconds;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (enabled && "GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith(ApiConstants.BASE_PATH)) {
            if (request.getHeader("Authorization") == null) {
                // anonymous browse — shareable within the tenant, keyed by tenant
                response.setHeader("Cache-Control", "public, max-age=" + ttlSeconds);
                response.setHeader("Vary", "X-Tenant-Id");
            } else {
                // a token could resolve a different tenant/user — never share it
                response.setHeader("Cache-Control", "no-store");
            }
        }
        chain.doFilter(request, response);
    }
}
