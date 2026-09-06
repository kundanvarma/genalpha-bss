package com.bss.insight.social;

import com.bss.insight.security.TenantRegistry;
import com.bss.insight.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves the CURRENT tenant's social line and the adapter that speaks it.
 * Per-tenant first (tenants.yml social-*); the deployment-wide properties are
 * only a fallback for the default demo tenant, so no tenant ever inherits
 * another's page or token by accident.
 */
@Component
public class SocialProviders {

    private final TenantRegistry registry;
    private final TenantScope tenantScope;
    private final MockSocialProvider mock;
    private final MetaGraphProvider meta;
    private final SocialConfig fallback;

    public SocialProviders(TenantRegistry registry, TenantScope tenantScope, RestClient.Builder builder,
            @Value("${bss.downstream.social-api-url:}") String fallbackUrl,
            @Value("${bss.downstream.social-account-id:}") String fallbackAccount,
            @Value("${bss.downstream.social-access-token:}") String fallbackToken) {
        this.registry = registry;
        this.tenantScope = tenantScope;
        this.mock = new MockSocialProvider(builder);
        this.meta = new MetaGraphProvider(builder);
        this.fallback = new SocialConfig("mock", fallbackUrl, null, fallbackAccount, null, fallbackToken, null);
    }

    public SocialConfig current() {
        return forTenant(tenantScope.currentTenantId());
    }

    public SocialConfig forTenant(String tenantId) {
        TenantRegistry.TenantEntry t = tenantId == null ? null : registry.byId(tenantId);
        if (t != null && t.getSocialApiUrl() != null && !t.getSocialApiUrl().isBlank()) {
            return new SocialConfig(t.getSocialProvider(), t.getSocialApiUrl(), t.getSocialApiVersion(),
                    t.getSocialAccountId(), t.getSocialIgUserId(), t.getSocialAccessToken(), t.getSocialAdsToken());
        }
        return fallback;
    }

    public SocialProvider providerFor(SocialConfig cfg) {
        return "meta".equals(cfg.providerName()) ? meta : mock;
    }
}
