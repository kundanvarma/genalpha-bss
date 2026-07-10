package com.bss.appointment.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

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
        return registry.defaultTenantId();
    }
}
