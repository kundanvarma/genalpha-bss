package com.bss.usage.client;

import com.bss.usage.security.TenantRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Which OCS a tenant charges on. Resolution order: the tenant's own
 * {@code ocs-*} block in tenants.yml, then the deployment-wide defaults
 * ({@code OCS_PROVIDER}, {@code OCS_BASE_URL}, {@code OCS_USERNAME},
 * {@code OCS_PASSWORD}). A blank base URL means "no online charging for this
 * tenant" — balances answer empty and the TMF654 facade says so.
 */
@Component
public class OcsSettings {

    /** Resolved settings for one tenant. */
    public record Binding(String tenantId, String provider, String baseUrl, String username, String password) {
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
        return new Binding(tenantId,
                blankTo(provider, defaultProvider),
                tenantOwn ? baseUrl.trim() : defaultBaseUrl,
                tenantOwn ? nz(user) : defaultUsername,
                tenantOwn ? nz(pass) : defaultPassword);
    }

    /** Every tenant bound to the named provider (the default tenant included). */
    public List<Binding> tenantsOn(String provider) {
        List<Binding> out = new ArrayList<>();
        for (TenantRegistry.TenantEntry t : tenants.getRegistry()) {
            Binding b = forTenant(t.getId());
            if (b.enabled() && provider.equals(b.provider())) {
                out.add(b);
            }
        }
        return out;
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
