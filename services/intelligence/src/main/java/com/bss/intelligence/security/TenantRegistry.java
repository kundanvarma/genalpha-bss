package com.bss.intelligence.security;

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
        /** Optional per-tenant AI stack: unset means the service-wide default. */
        private String aiProvider;
        private String aiBaseUrl;
        private String aiApiKey;
        private String aiModel;
        /** Client-credentials endpoint + machine client for THIS tenant's IdP
         * (only services that call other services use these). */
        private String tokenUri;
        private String machineClientId;
        private String machineClientSecret;

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

        public String getAiProvider() {
            return aiProvider;
        }

        public void setAiProvider(String aiProvider) {
            this.aiProvider = aiProvider;
        }

        public String getAiBaseUrl() {
            return aiBaseUrl;
        }

        public void setAiBaseUrl(String aiBaseUrl) {
            this.aiBaseUrl = aiBaseUrl;
        }

        public String getAiApiKey() {
            return aiApiKey;
        }

        public void setAiApiKey(String aiApiKey) {
            this.aiApiKey = aiApiKey;
        }

        public String getAiModel() {
            return aiModel;
        }

        public void setAiModel(String aiModel) {
            this.aiModel = aiModel;
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

        /** The market-intelligence subscription (tariff comparison) —
         * a per-tenant seam; the advisor's market half reads it. */
        private String marketFeedUrl;
        private String marketFeedToken;

        public String getMarketFeedUrl() { return marketFeedUrl; }
        public void setMarketFeedUrl(String v) { this.marketFeedUrl = v; }
        public String getMarketFeedToken() { return marketFeedToken; }
        public void setMarketFeedToken(String v) { this.marketFeedToken = v; }

        /** Tiered models: cheap for volume work, careful for judgment —
         * both live at once; blank falls back to ai-model. */
        private String aiModelFast;
        private String aiModelSmart;

        public String getAiModelFast() { return aiModelFast; }
        public void setAiModelFast(String v) { this.aiModelFast = v; }
        public String getAiModelSmart() { return aiModelSmart; }
        public void setAiModelSmart(String v) { this.aiModelSmart = v; }

        /** Whole-provider overrides per tier: a local cheap endpoint for
         * FAST, a frontier API for SMART — blank falls back to the shared
         * ai-provider/base-url/api-key. */
        private String aiProviderFast;
        private String aiBaseUrlFast;
        private String aiApiKeyFast;
        private String aiProviderSmart;
        private String aiBaseUrlSmart;
        private String aiApiKeySmart;

        public String getAiProviderFast() { return aiProviderFast; }
        public void setAiProviderFast(String v) { this.aiProviderFast = v; }
        public String getAiBaseUrlFast() { return aiBaseUrlFast; }
        public void setAiBaseUrlFast(String v) { this.aiBaseUrlFast = v; }
        public String getAiApiKeyFast() { return aiApiKeyFast; }
        public void setAiApiKeyFast(String v) { this.aiApiKeyFast = v; }
        public String getAiProviderSmart() { return aiProviderSmart; }
        public void setAiProviderSmart(String v) { this.aiProviderSmart = v; }
        public String getAiBaseUrlSmart() { return aiBaseUrlSmart; }
        public void setAiBaseUrlSmart(String v) { this.aiBaseUrlSmart = v; }
        public String getAiApiKeySmart() { return aiApiKeySmart; }
        public void setAiApiKeySmart(String v) { this.aiApiKeySmart = v; }

        public void setMachineClientSecret(String machineClientSecret) {
            this.machineClientSecret = machineClientSecret;
        }
    }
}
