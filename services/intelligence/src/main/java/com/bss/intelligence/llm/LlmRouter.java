package com.bss.intelligence.llm;

import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-tenant model routing: a tenant with its own AI stack in the registry
 * (provider + endpoint + key) gets its own adapter; everyone else uses the
 * service-wide default. GenAlpha on Claude while Nova runs an on-prem Ollama
 * is a registry entry, not a deployment. Adapters are built lazily and
 * cached per tenant.
 *
 * #422: the router also names each route's JURISDICTION (declared, else the
 * provider kind's default) and the tenant's FALLBACK per tier, so the governor
 * can refuse a route on residency before the prompt leaves, and retry once on
 * the second choice when the first is down.
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

    /** One provider configuration, resolved for one tier. */
    private record Stack(String provider, String baseUrl, String apiKey, String model, String jurisdiction) {
    }

    private LlmAdapter resolve() {
        return resolve(Tier.SMART); // unclassified work gets the careful model
    }

    private TenantRegistry.TenantEntry currentTenant() {
        return tenants.byId(tenantScope.currentTenantId());
    }

    private static boolean hasOwnStack(TenantRegistry.TenantEntry tenant) {
        return tenant != null && tenant.getAiProvider() != null && !tenant.getAiProvider().isBlank();
    }

    /** One tenant, several models AT ONCE: the tier picks the model
     * (falling back to the single ai-model when tiers are not set), and
     * each (tenant, model) pair gets its own cached adapter. */
    private LlmAdapter resolve(Tier tier) {
        TenantRegistry.TenantEntry tenant = currentTenant();
        if (!hasOwnStack(tenant)) {
            return defaultAdapter;
        }
        return cache.computeIfAbsent(tenant.getId() + ":" + tier, id -> build(tenant, primary(tenant, tier)));
    }

    private static String coalesce(String tiered, String shared) {
        return tiered != null && !tiered.isBlank() ? tiered : shared == null ? "" : shared;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * A tier's jurisdiction: the tier's own declaration, else the shared one
     * — but the shared one only while the tier really uses the shared provider
     * and URL (a tier that moved to another provider must not inherit "EU"
     * from the endpoint it left) — else the provider kind's default.
     */
    private static String jurisdiction(String tierDeclared, String sharedDeclared,
            String tierProvider, String tierBaseUrl, String provider) {
        String own = Jurisdiction.declared(tierDeclared);
        if (own != null) {
            return own;
        }
        if (blank(tierProvider) && blank(tierBaseUrl)) {
            String shared = Jurisdiction.declared(sharedDeclared);
            if (shared != null) {
                return shared;
            }
        }
        return Jurisdiction.defaultFor(provider);
    }

    /** WHOLE-PROVIDER tiers: each value resolves tier-first, shared second —
     * a local cheap endpoint for FAST and a frontier API for SMART can serve
     * the same tenant at once. */
    private static Stack primary(TenantRegistry.TenantEntry t, Tier tier) {
        boolean fast = tier == Tier.FAST;
        String tierProvider = fast ? t.getAiProviderFast() : t.getAiProviderSmart();
        String tierBase = fast ? t.getAiBaseUrlFast() : t.getAiBaseUrlSmart();
        String provider = coalesce(tierProvider, t.getAiProvider());
        return new Stack(provider,
                coalesce(tierBase, t.getAiBaseUrl()),
                coalesce(fast ? t.getAiApiKeyFast() : t.getAiApiKeySmart(), t.getAiApiKey()),
                coalesce(fast ? t.getAiModelFast() : t.getAiModelSmart(), t.getAiModel()),
                jurisdiction(fast ? t.getAiJurisdictionFast() : t.getAiJurisdictionSmart(), t.getAiJurisdiction(),
                        tierProvider, tierBase, provider));
    }

    /** The tenant's fallback for a tier, the same shape: tier keys first, shared fallback keys second. */
    private static Optional<Stack> fallback(TenantRegistry.TenantEntry t, Tier tier) {
        boolean fast = tier == Tier.FAST;
        String tierProvider = fast ? t.getAiFallbackProviderFast() : t.getAiFallbackProviderSmart();
        String tierBase = fast ? t.getAiFallbackBaseUrlFast() : t.getAiFallbackBaseUrlSmart();
        String provider = coalesce(tierProvider, t.getAiFallbackProvider());
        if (provider.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Stack(provider,
                coalesce(tierBase, t.getAiFallbackBaseUrl()),
                coalesce(fast ? t.getAiFallbackApiKeyFast() : t.getAiFallbackApiKeySmart(), t.getAiFallbackApiKey()),
                coalesce(fast ? t.getAiFallbackModelFast() : t.getAiFallbackModelSmart(), t.getAiFallbackModel()),
                jurisdiction(fast ? t.getAiFallbackJurisdictionFast() : t.getAiFallbackJurisdictionSmart(),
                        t.getAiFallbackJurisdiction(), tierProvider, tierBase, provider)));
    }

    private LlmAdapter build(TenantRegistry.TenantEntry tenant, Stack s) {
        // Each adapter gets its OWN copy of the builder: the adapters set a base
        // URL and default headers on it, and on a shared builder those piled up —
        // a later adapter (a fallback, another tenant) would have carried the
        // earlier one's x-api-key to its own provider.
        RestClient.Builder builder = this.builder.clone();
        return switch (s.provider()) {
            case "stub" -> new StubAdapter();
            case "openai-compatible" -> {
                if (s.baseUrl().isBlank() || s.model().isBlank()) {
                    throw new IllegalStateException("tenant '" + tenant.getId()
                            + "': openai-compatible needs ai-base-url and ai-model");
                }
                yield new OpenAiCompatibleAdapter(builder, s.baseUrl(), s.apiKey(), s.model());
            }
            case "anthropic" -> new AnthropicAdapter(builder, s.baseUrl(), s.apiKey(), s.model());
            default -> throw new IllegalStateException("tenant '" + tenant.getId()
                    + "': unknown ai-provider '" + s.provider() + "'");
        };
    }

    @Override
    public ModelRoute route(Tier tier) {
        TenantRegistry.TenantEntry tenant = currentTenant();
        if (!hasOwnStack(tenant)) {
            return ModelRoute.of(defaultAdapter, tier);
        }
        LlmAdapter adapter = resolve(tier);
        return new ModelRoute(adapter, adapter.provider(), adapter.model(), primary(tenant, tier).jurisdiction());
    }

    @Override
    public Optional<ModelRoute> fallbackRoute(Tier tier) {
        TenantRegistry.TenantEntry tenant = currentTenant();
        if (tenant == null) {
            return Optional.empty();
        }
        return fallback(tenant, tier).map(s -> {
            LlmAdapter adapter = cache.computeIfAbsent(tenant.getId() + ":" + tier + ":fallback",
                    id -> build(tenant, s));
            return new ModelRoute(adapter, adapter.provider(), adapter.model(), s.jurisdiction());
        });
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
    public LlmCompletion completeWithUsage(Tier tier, String system, String user) {
        return resolve(tier).completeWithUsage(tier, system, user);
    }

    @Override
    public String provider() {
        return resolve().provider();
    }

    @Override
    public String provider(Tier tier) {
        return resolve(tier).provider();
    }

    @Override
    public String model(Tier tier) {
        return resolve(tier).model();
    }

    @Override
    public String model() {
        return resolve().model();
    }
}
