package com.bss.intelligence.llm;

import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-tenant model routing: a tenant with its own AI stack in the registry
 * (provider + endpoint + key) gets its own adapter; everyone else uses the
 * service-wide default. GenAlpha on Claude while Nova runs an on-prem Ollama
 * is a registry entry, not a deployment. Adapters are built lazily and
 * cached per tenant.
 */
@Component
@Primary
public class LlmRouter implements LlmAdapter {

    private final LlmAdapter defaultAdapter;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final RestClient.Builder builder;
    private final Map<String, LlmAdapter> cache = new ConcurrentHashMap<>();

    public LlmRouter(java.util.List<LlmAdapter> adapters, TenantRegistry tenants,
            TenantScope tenantScope, RestClient.Builder builder) {
        // Exactly one provider bean exists (the @ConditionalOnProperty pick).
        this.defaultAdapter = adapters.stream().filter(a -> !(a instanceof LlmRouter))
                .findFirst().orElseThrow(() -> new IllegalStateException("no default LLM provider bean"));
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.builder = builder;
    }

    private LlmAdapter resolve() {
        return resolve(Tier.SMART); // unclassified work gets the careful model
    }

    /** One tenant, several models AT ONCE: the tier picks the model
     * (falling back to the single ai-model when tiers are not set), and
     * each (tenant, model) pair gets its own cached adapter. */
    private LlmAdapter resolve(Tier tier) {
        String tenantId = tenantScope.currentTenantId();
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getAiProvider() == null || tenant.getAiProvider().isBlank()) {
            return defaultAdapter;
        }
        return cache.computeIfAbsent(tenantId + ":" + tier, id -> build(tenant, tier));
    }

    private static String coalesce(String tiered, String shared) {
        return tiered != null && !tiered.isBlank() ? tiered : shared == null ? "" : shared;
    }

    private LlmAdapter build(TenantRegistry.TenantEntry tenant, Tier tier) {
        // WHOLE-PROVIDER tiers: each value resolves tier-first, shared
        // second — a local cheap endpoint for FAST and a frontier API for
        // SMART can serve the same tenant at once
        boolean fast = tier == Tier.FAST;
        String provider = coalesce(fast ? tenant.getAiProviderFast() : tenant.getAiProviderSmart(),
                tenant.getAiProvider());
        String baseUrl = coalesce(fast ? tenant.getAiBaseUrlFast() : tenant.getAiBaseUrlSmart(),
                tenant.getAiBaseUrl());
        String apiKey = coalesce(fast ? tenant.getAiApiKeyFast() : tenant.getAiApiKeySmart(),
                tenant.getAiApiKey());
        String model = coalesce(fast ? tenant.getAiModelFast() : tenant.getAiModelSmart(),
                tenant.getAiModel());
        return switch (provider) {
            case "stub" -> new StubAdapter();
            case "openai-compatible" -> {
                if (baseUrl.isBlank() || model.isBlank()) {
                    throw new IllegalStateException("tenant '" + tenant.getId()
                            + "': openai-compatible needs ai-base-url and ai-model");
                }
                yield new OpenAiCompatibleAdapter(builder, baseUrl, apiKey, model);
            }
            case "anthropic" -> new AnthropicAdapter(builder, baseUrl, apiKey, model);
            default -> throw new IllegalStateException("tenant '" + tenant.getId()
                    + "': unknown ai-provider '" + provider + "'");
        };
    }

    @Override
    public String complete(String system, String user) {
        return resolve().complete(system, user);
    }

    @Override
    public String complete(Tier tier, String system, String user) {
        return resolve(tier).complete(system, user);
    }

    @Override
    public String provider() {
        return resolve().provider();
    }

    @Override
    public String model() {
        return resolve().model();
    }
}
