package com.bss.payment.service;

import com.bss.payment.dto.PspConfigRequest;
import com.bss.payment.dto.PspConfigView;
import com.bss.payment.dto.PspTestResult;
import com.bss.payment.entity.PspConfig;
import com.bss.payment.repository.PspConfigRepository;
import com.bss.payment.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's PSP menu — CRUD over per-tenant provider bindings, plus the
 * default-provider lookup the payment path uses. RLS scopes every row.
 */
@Service
public class PspConfigService {

    private static final Set<String> KNOWN = Set.of("mock", "mockbank", "stripe", "klarna", "paypal", "vipps", "mmg");

    private final PspConfigRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public PspConfigService(PspConfigRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<PspConfigView> listForCurrentTenant() {
        return repository.findByTenantIdOrderByDisplayNameAsc(tenantScope.currentTenantId())
                .stream().map(PspConfigService::toView).toList();
    }

    /** The PSP a tenant charges through: the default-flagged enabled provider,
     * else the first enabled — or empty (the deployment's global PSP). */
    @Transactional(readOnly = true)
    public Optional<PspConfig> defaultForCurrentTenant() {
        return repository.findByTenantIdAndEnabledTrue(tenantScope.currentTenantId()).stream()
                .min(Comparator.comparing((PspConfig c) -> !c.isDefault())
                        .thenComparing(c -> String.valueOf(c.getDisplayName())));
    }

    @Transactional
    public PspConfigView upsert(PspConfigRequest dto) {
        String provider = dto.provider();
        if (provider == null || !KNOWN.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider is required and must be one of " + KNOWN);
        }
        String tenant = tenantScope.currentTenantId();
        PspConfig cfg = repository.findByTenantIdAndProvider(tenant, provider).orElseGet(() -> {
            PspConfig fresh = new PspConfig();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenant);
            fresh.setProvider(provider);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        cfg.setDisplayName(dto.displayName() == null ? provider : dto.displayName());
        cfg.setBaseUrl(dto.baseUrl());
        cfg.setSecretRef(dto.secretRef());
        cfg.setWebhookSecretRef(dto.webhookSecretRef());
        cfg.setMethods(json(dto.methods()));
        cfg.setDefault(Boolean.TRUE.equals(dto.isDefault()));
        if (dto.priority() != null && !dto.priority().isNull()) {
            cfg.setPriority(Integer.parseInt(dto.priority().asText()));
        }
        cfg.setCurrencies(json(dto.currencies()));
        cfg.setEnabled(!Boolean.FALSE.equals(dto.enabled()));
        cfg.setLastUpdate(OffsetDateTime.now());
        if (cfg.isDefault()) {
            for (PspConfig other : repository.findByTenantIdOrderByDisplayNameAsc(tenant)) {
                if (!other.getId().equals(cfg.getId()) && other.isDefault()) {
                    other.setDefault(false);
                    repository.save(other);
                }
            }
        }
        return toView(repository.save(cfg));
    }

    @Transactional(readOnly = true)
    public Optional<PspConfig> forTenantAndProvider(String tenant, String provider) {
        return repository.findByTenantIdAndProvider(tenant, provider);
    }

    /** The payment methods the current tenant offers (from its enabled PSPs), or the
     * built-in card default when no PSP is configured. */
    @Transactional(readOnly = true)
    public List<String> methodsForCurrentTenant() {
        List<PspConfig> enabled = repository.findByTenantIdAndEnabledTrue(tenantScope.currentTenantId());
        java.util.LinkedHashSet<String> methods = new java.util.LinkedHashSet<>();
        for (PspConfig c : enabled) {
            for (String m : parseMethods(c.getMethods())) {
                methods.add(m);
            }
        }
        if (methods.isEmpty()) {
            methods.add("card");
        }
        return new java.util.ArrayList<>(methods);
    }

    /** Every enabled provider config for a tenant — the pool orchestration draws
     * its failover candidates from. */
    @Transactional(readOnly = true)
    public List<PspConfig> enabledForTenant(String tenant) {
        return repository.findByTenantIdAndEnabledTrue(tenant);
    }

    /** The ordered pool of CARD providers that handle a currency, for the current
     * tenant: priority ascending (the routing rule), the default first on a tie.
     * The authorize path tries them in order and fails over past an unreachable
     * one. Empty → the caller uses the deployment's global PSP (unchanged). */
    @Transactional(readOnly = true)
    public List<PspConfig> cardCandidates(String currency) {
        return repository.findByTenantIdAndEnabledTrue(tenantScope.currentTenantId()).stream()
                .filter(c -> parseMethods(c.getMethods()).contains("card"))
                .filter(c -> currencyMatches(c, currency))
                .sorted(Comparator.comparingInt(PspConfig::getPriority)
                        .thenComparing(c -> c.isDefault() ? 0 : 1)
                        .thenComparing(c -> String.valueOf(c.getDisplayName())))
                .toList();
    }

    /** A provider handles a currency if it lists none (any) or lists this one. */
    private boolean currencyMatches(PspConfig c, String currency) {
        if (c.getCurrencies() == null || c.getCurrencies().isBlank() || currency == null) {
            return true;
        }
        try {
            List<String> codes = mapper.readValue(c.getCurrencies(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            return codes.isEmpty() || codes.stream().anyMatch(x -> x.equalsIgnoreCase(currency));
        } catch (Exception e) {
            return true;   // a malformed filter never blocks a charge
        }
    }

    /** The provider that serves a method for a tenant (e.g. 'klarna' → the klarna PSP). */
    @Transactional(readOnly = true)
    public Optional<PspConfig> providerForMethod(String tenant, String method) {
        return repository.findByTenantIdAndEnabledTrue(tenant).stream()
                .filter(c -> parseMethods(c.getMethods()).contains(method))
                .findFirst();
    }

    private List<String> parseMethods(String json) {
        if (json == null || json.isBlank()) {
            return List.of("card");
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of("card");
        }
    }

    /** Test connection: reachability only — never a money operation. Pings the
     * configured base URL's /health with a short timeout; no base URL means an
     * in-process (or SDK-default) adapter, reported honestly, not probed. */
    @Transactional(readOnly = true)
    public PspTestResult testConnection(String provider) {
        PspConfig cfg = repository.findByTenantIdAndProvider(tenantScope.currentTenantId(), provider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "provider '" + provider + "' is not configured for this tenant"));
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return PspTestResult.said(provider, true,
                    "no base URL configured — in-process/default adapter, nothing to probe");
        }
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpResponse<Void> resp = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.getBaseUrl() + "/health"))
                            .timeout(java.time.Duration.ofSeconds(4)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return PspTestResult.reached(provider, resp.statusCode(),
                    "reachability probe of " + cfg.getBaseUrl() + "/health — not a payment");
        } catch (Exception e) {
            return PspTestResult.said(provider, false, "unreachable: " + e.getMessage());
        }
    }

    @Transactional
    public void delete(String provider) {
        repository.findByTenantIdAndProvider(tenantScope.currentTenantId(), provider)
                .ifPresent(repository::delete);
    }

    /** A posted list is stored as the JSON it is; an already-encoded string is stored verbatim. */
    private static String json(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.textValue() : v.toString();
    }

    /** The secret is a reference only — the API key is never returned. */
    private static PspConfigView toView(PspConfig c) {
        return new PspConfigView(c.getProvider(), c.getDisplayName(), c.getBaseUrl(), c.getSecretRef(),
                c.getMethods(), c.isDefault(), c.getPriority(), c.getCurrencies(), c.isEnabled(), "PspConfig");
    }
}
