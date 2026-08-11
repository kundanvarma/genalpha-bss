package com.bss.document.service;

import com.bss.document.entity.ContentProviderConfig;
import com.bss.document.exception.BadRequestException;
import com.bss.document.exception.NotFoundException;
import com.bss.document.repository.ContentProviderConfigRepository;
import com.bss.document.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The current tenant's external-CMS binding. One row per tenant (RLS-scoped);
 * no row means the tenant uses the hosted DAM. Upsert/delete is back-office
 * (document:write); the secret is a reference, never the value.
 */
@Service
public class ContentProviderConfigService {

    private static final Set<String> KNOWN_PROVIDERS = Set.of("sanity", "http");

    private final ContentProviderConfigRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentProviderConfigService(ContentProviderConfigRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    /** The provider bound to the request's tenant, or empty → hosted DAM. */
    @Transactional(readOnly = true)
    public Optional<ContentProviderConfig> forCurrentTenant() {
        return repository.findByTenantId(tenantScope.currentTenantId());
    }

    @Transactional
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String provider = str(dto.get("provider"));
        if (provider == null || !KNOWN_PROVIDERS.contains(provider)) {
            throw new BadRequestException("provider is required and must be one of " + KNOWN_PROVIDERS);
        }
        String tenant = tenantScope.currentTenantId();
        ContentProviderConfig cfg = repository.findByTenantId(tenant).orElseGet(() -> {
            ContentProviderConfig fresh = new ContentProviderConfig();
            fresh.setTenantId(tenant);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        cfg.setProvider(provider);
        cfg.setBaseUrl(str(dto.get("baseUrl")));
        cfg.setProjectId(str(dto.get("projectId")));
        cfg.setDataset(str(dto.get("dataset")));
        cfg.setSecretRef(str(dto.get("secretRef")));
        cfg.setWebhookSecretRef(str(dto.get("webhookSecretRef")));
        cfg.setDirectUrl(Boolean.TRUE.equals(dto.get("directUrl")));
        cfg.setConfig(configJson(dto.get("config")));
        cfg.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(cfg));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> currentAsMap() {
        return toMap(forCurrentTenant()
                .orElseThrow(() -> NotFoundException.forResource("ContentProviderConfig", tenantScope.currentTenantId())));
    }

    @Transactional
    public void deleteForCurrentTenant() {
        repository.findByTenantId(tenantScope.currentTenantId()).ifPresent(repository::delete);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** config may arrive as a JSON object (store as JSON) or a JSON string (store as-is). */
    private String configJson(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new BadRequestException("config is not serialisable JSON");
        }
    }

    /** The secret is a REFERENCE only — the token value is never returned. */
    private Map<String, Object> toMap(ContentProviderConfig c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", c.getTenantId());
        map.put("provider", c.getProvider());
        if (c.getBaseUrl() != null) map.put("baseUrl", c.getBaseUrl());
        if (c.getProjectId() != null) map.put("projectId", c.getProjectId());
        if (c.getDataset() != null) map.put("dataset", c.getDataset());
        if (c.getSecretRef() != null) map.put("secretRef", c.getSecretRef());
        if (c.getWebhookSecretRef() != null) map.put("webhookSecretRef", c.getWebhookSecretRef());
        map.put("directUrl", c.isDirectUrl());
        map.put("@type", "ContentProviderConfig");
        return map;
    }
}
