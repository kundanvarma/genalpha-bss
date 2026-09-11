package com.bss.som.client;

import com.bss.som.security.TenantRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which OCS a tenant charges on. Resolution order: the tenant's own
 * {@code ocs-*} block in tenants.yml, then the deployment-wide defaults
 * ({@code OCS_PROVIDER}, {@code OCS_BASE_URL}, {@code OCS_USERNAME},
 * {@code OCS_PASSWORD}). A blank base URL means "no online charging for this
 * tenant" — every seam call is a logged no-op, activation is never blocked.
 */
@Component
public class OcsSettings {

    /** Resolved settings for one tenant. */
    public record Binding(String provider, String baseUrl, String username, String password) {
        public boolean enabled() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    private final TenantRegistry tenants;
    private final String defaultProvider;
    private final String defaultBaseUrl;
    private final String defaultUsername;
    private final String defaultPassword;

    public OcsSettings(TenantRegistry tenants,
            @Value("${bss.downstream.ocs-provider:http}") String defaultProvider,
            @Value("${bss.downstream.ocs-base-url:}") String defaultBaseUrl,
            @Value("${bss.downstream.ocs-username:}") String defaultUsername,
            @Value("${bss.downstream.ocs-password:}") String defaultPassword) {
        this.tenants = tenants;
        this.defaultProvider = blankTo(defaultProvider, "http");
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
        this.defaultUsername = defaultUsername == null ? "" : defaultUsername;
        this.defaultPassword = defaultPassword == null ? "" : defaultPassword;
    }

    public Binding forTenant(String tenantId) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        String provider = t == null ? null : t.getOcsProvider();
        String baseUrl = t == null ? null : t.getOcsBaseUrl();
        String user = t == null ? null : t.getOcsUsername();
        String pass = t == null ? null : t.getOcsPassword();
        boolean tenantOwn = baseUrl != null && !baseUrl.isBlank();
        // a tenant that names its own OCS uses its own credential, never the default one
        return new Binding(
                blankTo(provider, defaultProvider),
                tenantOwn ? baseUrl.trim() : defaultBaseUrl,
                tenantOwn ? nz(user) : defaultUsername,
                tenantOwn ? nz(pass) : defaultPassword);
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
