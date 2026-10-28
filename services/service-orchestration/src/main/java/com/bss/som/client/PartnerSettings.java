package com.bss.som.client;

import com.bss.som.security.TenantRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which partner platform a tenant activates partner services on: the tenant's
 * own {@code partner-platform} / {@code partner-base-url} in tenants.yml, else
 * the deployment-wide defaults ({@code PARTNER_PLATFORM}, {@code PARTNER_BASE_URL}).
 */
@Component
public class PartnerSettings {

    public record Binding(String platform, String baseUrl) {
    }

    private final TenantRegistry tenants;
    private final String defaultPlatform;
    private final String defaultBaseUrl;

    public PartnerSettings(TenantRegistry tenants,
            @Value("${bss.partner.platform:mock}") String defaultPlatform,
            @Value("${bss.partner.base-url:}") String defaultBaseUrl) {
        this.tenants = tenants;
        this.defaultPlatform = defaultPlatform == null || defaultPlatform.isBlank() ? "mock" : defaultPlatform.trim();
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
    }

    public Binding forTenant(String tenantId) {
        TenantRegistry.TenantEntry t = tenants.byId(tenantId);
        String platform = t == null || t.getPartnerPlatform() == null || t.getPartnerPlatform().isBlank()
                ? defaultPlatform : t.getPartnerPlatform().trim();
        String baseUrl = t == null || t.getPartnerBaseUrl() == null || t.getPartnerBaseUrl().isBlank()
                ? defaultBaseUrl : t.getPartnerBaseUrl().trim();
        return new Binding(platform, baseUrl);
    }
}
