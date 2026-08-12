package com.bss.payment.service;

import com.bss.payment.entity.PspConfig;
import com.bss.payment.repository.PspConfigRepository;
import com.bss.payment.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    private static final Set<String> KNOWN = Set.of("mock", "stripe", "klarna", "paypal");

    private final PspConfigRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public PspConfigService(PspConfigRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForCurrentTenant() {
        return repository.findByTenantIdOrderByDisplayNameAsc(tenantScope.currentTenantId())
                .stream().map(PspConfigService::toMap).toList();
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
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String provider = str(dto.get("provider"));
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
        cfg.setDisplayName(str(dto.getOrDefault("displayName", provider)));
        cfg.setBaseUrl(str(dto.get("baseUrl")));
        cfg.setSecretRef(str(dto.get("secretRef")));
        cfg.setWebhookSecretRef(str(dto.get("webhookSecretRef")));
        cfg.setMethods(json(dto.get("methods")));
        cfg.setDefault(Boolean.TRUE.equals(dto.get("isDefault")));
        cfg.setEnabled(!Boolean.FALSE.equals(dto.get("enabled")));
        cfg.setLastUpdate(OffsetDateTime.now());
        if (cfg.isDefault()) {
            for (PspConfig other : repository.findByTenantIdOrderByDisplayNameAsc(tenant)) {
                if (!other.getId().equals(cfg.getId()) && other.isDefault()) {
                    other.setDefault(false);
                    repository.save(other);
                }
            }
        }
        return toMap(repository.save(cfg));
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

    @Transactional
    public void delete(String provider) {
        repository.findByTenantIdAndProvider(tenantScope.currentTenantId(), provider)
                .ifPresent(repository::delete);
    }

    private String json(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not serialisable JSON");
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** The secret is a reference only — the API key is never returned. */
    private static Map<String, Object> toMap(PspConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", c.getProvider());
        m.put("displayName", c.getDisplayName());
        if (c.getBaseUrl() != null) m.put("baseUrl", c.getBaseUrl());
        if (c.getSecretRef() != null) m.put("secretRef", c.getSecretRef());
        if (c.getMethods() != null) m.put("methods", c.getMethods());
        m.put("isDefault", c.isDefault());
        m.put("enabled", c.isEnabled());
        m.put("@type", "PspConfig");
        return m;
    }
}
