package com.bss.stock.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Which tenant is this request acting inside? Authenticated callers: the
 * registry entry for their verified token issuer. Anonymous callers (public
 * catalog browsing): the deployment's default tenant. Every repository query
 * carries this — a tenant can never see or touch another tenant's rows, and
 * cross-tenant ids read as 404, not 403.
 */
@Component
public class TenantScope {

    private final TenantRegistry registry;

    public TenantScope(TenantRegistry registry) {
        this.registry = registry;
    }

    public String currentTenantId() {
        String contextual = TenantContext.current();
        if (contextual != null) {
            return contextual;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && jwt.getToken().getIssuer() != null) {
            String issuer = jwt.getToken().getIssuer().toString();
            TenantRegistry.TenantEntry tenant = registry.byIssuer(issuer);
            if (tenant == null) {
                // Unreachable for real tokens: the issuer resolver already
                // rejected unregistered issuers at authentication time.
                throw new IllegalStateException("token issuer is not in the tenant registry: " + issuer);
            }
            return tenant.getId();
        }
        return headerTenant().orElse(registry.defaultTenantId());
    }

    /**
     * Anonymous traffic only: the gateway maps the request's hostname to a
     * tenant and says so in X-Tenant-Id (stripping any inbound copy first).
     * For authenticated callers the verified issuer always wins, so the
     * header can never move a user across tenants — it only selects which
     * tenant's PUBLIC face (catalog, guest cart) an anonymous visitor sees.
     */
    private java.util.Optional<String> headerTenant() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            String header = servlet.getRequest().getHeader("X-Tenant-Id");
            if (header != null && registry.byId(header) != null) {
                return java.util.Optional.of(header);
            }
        }
        return java.util.Optional.empty();
    }
}
