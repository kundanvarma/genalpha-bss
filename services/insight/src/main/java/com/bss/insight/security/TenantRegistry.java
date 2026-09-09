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

        private boolean sandbox;

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

        /** The social seam, PER TENANT: which platform adapter ('mock' | 'meta'),
         * its API base url + version, the brand's page/account id, an optional
         * Instagram professional account, the page access token and (Meta) the
         * ads token for Custom Audiences. Empty url = this tenant has no social
         * line wired; the deployment-wide fallback keeps the default demo tenant
         * working. Two operators never share a page or a token. */
        private String socialProvider;
        private String socialApiUrl;
        private String socialApiVersion;
        private String socialAccountId;
        private String socialIgUserId;
        private String socialAccessToken;
        private String socialAdsToken;
        /** Desk learning (tenants.yml desk-learning): may this tenant's desks report
         * how they are used, so the platform can suggest improvements? Off by default. */
        private boolean deskLearning;

        public boolean isSandbox() {
            return sandbox;
        }

        public void setSandbox(boolean v) {
            this.sandbox = v;
        }

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
        public String getSocialProvider() { return socialProvider; }
        public void setSocialProvider(String v) { this.socialProvider = v; }
        public String getSocialApiUrl() { return socialApiUrl; }
        public void setSocialApiUrl(String v) { this.socialApiUrl = v; }
        public String getSocialApiVersion() { return socialApiVersion; }
        public void setSocialApiVersion(String v) { this.socialApiVersion = v; }
        public String getSocialAccountId() { return socialAccountId; }
        public void setSocialAccountId(String v) { this.socialAccountId = v; }
        public String getSocialIgUserId() { return socialIgUserId; }
        public void setSocialIgUserId(String v) { this.socialIgUserId = v; }
        public String getSocialAccessToken() { return socialAccessToken; }
        public void setSocialAccessToken(String v) { this.socialAccessToken = v; }
        public String getSocialAdsToken() { return socialAdsToken; }
        public boolean isDeskLearning() { return deskLearning; }
        public void setDeskLearning(boolean v) { this.deskLearning = v; }
        public void setSocialAdsToken(String v) { this.socialAdsToken = v; }
        public void setAnalyticsDataToken(String v) { this.analyticsDataToken = v; }
    }
}
