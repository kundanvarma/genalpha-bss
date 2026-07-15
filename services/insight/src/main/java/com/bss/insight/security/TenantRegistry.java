package com.bss.insight.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The deployment's tenants: which OIDC issuers are trusted and which tenant
 * each one IS. Tenancy derives from the verified token issuer — never from a
 * user-editable claim — so a tenant can be a Keycloak realm here, a Cognito
 * pool or an Entra tenant in the cloud, without code changes. Anonymous
 * traffic belongs to the default tenant until the gateway maps hostnames.
 */
@Component
@ConfigurationProperties(prefix = "bss.tenants")
public class TenantRegistry {

    private String defaultTenant = "genalpha";
    private List<TenantEntry> registry = new ArrayList<>();

    public TenantEntry byIssuer(String issuer) {
        if (issuer == null) {
            return null;
        }
        return registry.stream().filter(t -> issuer.equals(t.getIssuer())).findFirst().orElse(null);
    }

    public TenantEntry byId(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        return registry.stream().filter(t -> tenantId.equals(t.getId())).findFirst().orElse(null);
    }

    public String defaultTenantId() {
        return defaultTenant;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public List<TenantEntry> getRegistry() {
        return registry;
    }

    public void setRegistry(List<TenantEntry> registry) {
        this.registry = registry;
    }

    public static class TenantEntry {

        private String id;
        private String issuer;
        /** Backchannel JWKS endpoint; empty means discover from the issuer. */
        private String jwksUri;
        /** Client-credentials endpoint + machine client for THIS tenant's IdP
         * (only services that call other services use these). */
        private String tokenUri;
        private String machineClientId;
        private String machineClientSecret;

        /** The analytics seam: 'internal' keeps events first-party only;
         * 'ga4' forwards them to the tenant's own property via the
         * Measurement Protocol. Bring your own analytics — per tenant. */
        private String analyticsProvider;
        private String analyticsMpUrl;
        private String analyticsMeasurementId;
        private String analyticsApiSecret;
        /** GA4 Data API seam (audience catalog import): base url, property
         * and an OAuth bearer — real Google or the mock, config apart. */
        private String analyticsDataUrl;
        private String analyticsPropertyId;
        private String analyticsDataToken;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getMachineClientId() {
            return machineClientId;
        }

        public void setMachineClientId(String machineClientId) {
            this.machineClientId = machineClientId;
        }

        public String getMachineClientSecret() {
            return machineClientSecret;
        }

        public void setMachineClientSecret(String machineClientSecret) {
            this.machineClientSecret = machineClientSecret;
        }

        public String getAnalyticsProvider() { return analyticsProvider; }
        public void setAnalyticsProvider(String v) { this.analyticsProvider = v; }
        public String getAnalyticsMpUrl() { return analyticsMpUrl; }
        public void setAnalyticsMpUrl(String v) { this.analyticsMpUrl = v; }
        public String getAnalyticsMeasurementId() { return analyticsMeasurementId; }
        public void setAnalyticsMeasurementId(String v) { this.analyticsMeasurementId = v; }
        public String getAnalyticsApiSecret() { return analyticsApiSecret; }
        public void setAnalyticsApiSecret(String v) { this.analyticsApiSecret = v; }
        public String getAnalyticsDataUrl() { return analyticsDataUrl; }
        public void setAnalyticsDataUrl(String v) { this.analyticsDataUrl = v; }
        public String getAnalyticsPropertyId() { return analyticsPropertyId; }
        public void setAnalyticsPropertyId(String v) { this.analyticsPropertyId = v; }
        public String getAnalyticsDataToken() { return analyticsDataToken; }
        public void setAnalyticsDataToken(String v) { this.analyticsDataToken = v; }
    }
}
