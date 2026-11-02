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
        /** THE OVERLAY SEAM: the legacy estate's incident system — the
         * workforce works LEGACY backlogs through it. */
        private String legacyTicketBaseUrl;
        /** The operator's storefront language — copilots write customer-facing
         * copy (banners, offering names, descriptions) in it, not in English. */
        private String locale;

        public String getLegacyTicketBaseUrl() { return legacyTicketBaseUrl; }
        public void setLegacyTicketBaseUrl(String v) { this.legacyTicketBaseUrl = v; }
        public String getLocale() { return locale; }
        public void setLocale(String v) { this.locale = v; }

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

        /** Bring-your-own speech-to-text (Whisper-shaped) — a per-tenant seam;
         * blank = no server STT, the console mic falls back to browser speech. */
        private String speechUrl;
        private String speechToken;

        public String getSpeechUrl() { return speechUrl; }
        public void setSpeechUrl(String v) { this.speechUrl = v; }
        public String getSpeechToken() { return speechToken; }
        public void setSpeechToken(String v) { this.speechToken = v; }

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

        /** The digital workforce's brain (#283). The worker-controller used to
         * read these and hand the key to the worker; now the MODEL DOOR reads
         * them and the key never leaves this component. Independent of ai-*:
         * copilots and workers may run on different providers. */
        private String workerAiProvider;
        private String workerAiBaseUrl;
        private String workerAiApiKey;
        private String workerAiModel;

        public String getWorkerAiProvider() { return workerAiProvider; }
        public void setWorkerAiProvider(String v) { this.workerAiProvider = v; }
        public String getWorkerAiBaseUrl() { return workerAiBaseUrl; }
        public void setWorkerAiBaseUrl(String v) { this.workerAiBaseUrl = v; }
        public String getWorkerAiApiKey() { return workerAiApiKey; }
        public void setWorkerAiApiKey(String v) { this.workerAiApiKey = v; }
        public String getWorkerAiModel() { return workerAiModel; }
        public void setWorkerAiModel(String v) { this.workerAiModel = v; }

        /** T-P3 gate: may this tenant's AI use-cases send RAW customer data
         * to a remote model? Opt-in, never assumed. */
        private boolean aiRawExposure;
        public boolean isAiRawExposure() { return aiRawExposure; }
        public void setAiRawExposure(boolean v) { this.aiRawExposure = v; }

        /** #422 EU-ONLY ROUTE: {@code ai-data-residency: eu} refuses any model
         * whose jurisdiction is not EU or self-hosted, fallbacks included.
         * Absent = any provider (today's behaviour); any other word refuses
         * every call, because a residency rule with a typo must not mean "any". */
        private String aiDataResidency;
        /** Declared jurisdiction of the tenant's own AI stack (EU | US | self-hosted).
         * Blank = derived from the provider kind, as before. The shared value
         * applies to a tier only while that tier uses the shared provider and URL. */
        private String aiJurisdiction;
        private String aiJurisdictionFast;
        private String aiJurisdictionSmart;
        /** #422 FALLBACK: a second provider per tier, tried once when the first
         * fails on a connection error, a timeout, a 5xx or a 429. Configured
         * exactly like the primary (the key is an env reference, never a
         * literal); tier keys first, shared second. Blank = no fallback. */
        private String aiFallbackProvider;
        private String aiFallbackBaseUrl;
        private String aiFallbackApiKey;
        private String aiFallbackModel;
        private String aiFallbackJurisdiction;
        private String aiFallbackProviderFast;
        private String aiFallbackBaseUrlFast;
        private String aiFallbackApiKeyFast;
        private String aiFallbackModelFast;
        private String aiFallbackJurisdictionFast;
        private String aiFallbackProviderSmart;
        private String aiFallbackBaseUrlSmart;
        private String aiFallbackApiKeySmart;
        private String aiFallbackModelSmart;
        private String aiFallbackJurisdictionSmart;
        /** Declared jurisdiction of the worker model behind the model door. */
        private String workerAiJurisdiction;

        public String getAiDataResidency() { return aiDataResidency; }
        public void setAiDataResidency(String v) { this.aiDataResidency = v; }
        public String getAiJurisdiction() { return aiJurisdiction; }
        public void setAiJurisdiction(String v) { this.aiJurisdiction = v; }
        public String getAiJurisdictionFast() { return aiJurisdictionFast; }
        public void setAiJurisdictionFast(String v) { this.aiJurisdictionFast = v; }
        public String getAiJurisdictionSmart() { return aiJurisdictionSmart; }
        public void setAiJurisdictionSmart(String v) { this.aiJurisdictionSmart = v; }
        public String getAiFallbackProvider() { return aiFallbackProvider; }
        public void setAiFallbackProvider(String v) { this.aiFallbackProvider = v; }
        public String getAiFallbackBaseUrl() { return aiFallbackBaseUrl; }
        public void setAiFallbackBaseUrl(String v) { this.aiFallbackBaseUrl = v; }
        public String getAiFallbackApiKey() { return aiFallbackApiKey; }
        public void setAiFallbackApiKey(String v) { this.aiFallbackApiKey = v; }
        public String getAiFallbackModel() { return aiFallbackModel; }
        public void setAiFallbackModel(String v) { this.aiFallbackModel = v; }
        public String getAiFallbackJurisdiction() { return aiFallbackJurisdiction; }
        public void setAiFallbackJurisdiction(String v) { this.aiFallbackJurisdiction = v; }
        public String getAiFallbackProviderFast() { return aiFallbackProviderFast; }
        public void setAiFallbackProviderFast(String v) { this.aiFallbackProviderFast = v; }
        public String getAiFallbackBaseUrlFast() { return aiFallbackBaseUrlFast; }
        public void setAiFallbackBaseUrlFast(String v) { this.aiFallbackBaseUrlFast = v; }
        public String getAiFallbackApiKeyFast() { return aiFallbackApiKeyFast; }
        public void setAiFallbackApiKeyFast(String v) { this.aiFallbackApiKeyFast = v; }
        public String getAiFallbackModelFast() { return aiFallbackModelFast; }
        public void setAiFallbackModelFast(String v) { this.aiFallbackModelFast = v; }
        public String getAiFallbackJurisdictionFast() { return aiFallbackJurisdictionFast; }
        public void setAiFallbackJurisdictionFast(String v) { this.aiFallbackJurisdictionFast = v; }
        public String getAiFallbackProviderSmart() { return aiFallbackProviderSmart; }
        public void setAiFallbackProviderSmart(String v) { this.aiFallbackProviderSmart = v; }
        public String getAiFallbackBaseUrlSmart() { return aiFallbackBaseUrlSmart; }
        public void setAiFallbackBaseUrlSmart(String v) { this.aiFallbackBaseUrlSmart = v; }
        public String getAiFallbackApiKeySmart() { return aiFallbackApiKeySmart; }
        public void setAiFallbackApiKeySmart(String v) { this.aiFallbackApiKeySmart = v; }
        public String getAiFallbackModelSmart() { return aiFallbackModelSmart; }
        public void setAiFallbackModelSmart(String v) { this.aiFallbackModelSmart = v; }
        public String getAiFallbackJurisdictionSmart() { return aiFallbackJurisdictionSmart; }
        public void setAiFallbackJurisdictionSmart(String v) { this.aiFallbackJurisdictionSmart = v; }
        public String getWorkerAiJurisdiction() { return workerAiJurisdiction; }
        public void setWorkerAiJurisdiction(String v) { this.workerAiJurisdiction = v; }

        public void setMachineClientSecret(String machineClientSecret) {
            this.machineClientSecret = machineClientSecret;
        }
    }
}
