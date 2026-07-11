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
        String tenantId = tenantScope.currentTenantId();
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getAiProvider() == null || tenant.getAiProvider().isBlank()) {
            return defaultAdapter;
        }
        return cache.computeIfAbsent(tenantId, id -> build(tenant));
    }

    private LlmAdapter build(TenantRegistry.TenantEntry tenant) {
        String apiKey = tenant.getAiApiKey() == null ? "" : tenant.getAiApiKey();
        String model = tenant.getAiModel() == null ? "" : tenant.getAiModel();
        return switch (tenant.getAiProvider()) {
            case "stub" -> new StubAdapter();
            case "openai-compatible" -> {
                if (tenant.getAiBaseUrl() == null || model.isBlank()) {
                    throw new IllegalStateException("tenant '" + tenant.getId()
                            + "': openai-compatible needs ai-base-url and ai-model");
                }
                yield new OpenAiCompatibleAdapter(builder, tenant.getAiBaseUrl(), apiKey, model);
            }
            case "anthropic" -> new AnthropicAdapter(builder,
                    tenant.getAiBaseUrl() == null ? "" : tenant.getAiBaseUrl(), apiKey, model);
            default -> throw new IllegalStateException("tenant '" + tenant.getId()
                    + "': unknown ai-provider '" + tenant.getAiProvider() + "'");
        };
    }

    @Override
    public String complete(String system, String user) {
        return resolve().complete(system, user);
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
