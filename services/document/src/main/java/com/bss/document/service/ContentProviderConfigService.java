package com.bss.document.service;

import com.bss.document.dto.ContentProviderConfigRequest;
import com.bss.document.dto.ContentProviderConfigView;
import com.bss.document.entity.ContentProviderConfig;
import com.bss.document.exception.BadRequestException;
import com.bss.document.exception.NotFoundException;
import com.bss.document.repository.ContentProviderConfigRepository;
import com.bss.document.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The current tenant's external-CMS binding. One row per tenant (RLS-scoped);
 * no row means the tenant uses the hosted DAM. Upsert/delete is back-office
 * (document:write); the secret is a reference, never the value.
 */
@Service
public class ContentProviderConfigService {

    /** Printed in the refusal, in the order the wire has always shown it. */
    private static final Set<String> KNOWN_PROVIDERS =
            new LinkedHashSet<>(List.of("http", "sanity"));

    private final ContentProviderConfigRepository repository;
    private final TenantScope tenantScope;

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
    public ContentProviderConfigView upsert(ContentProviderConfigRequest dto) {
        String provider = dto.provider();
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
        cfg.setBaseUrl(dto.baseUrl());
        cfg.setProjectId(dto.projectId());
        cfg.setDataset(dto.dataset());
        cfg.setSecretRef(dto.secretRef());
        cfg.setWebhookSecretRef(dto.webhookSecretRef());
        cfg.setDirectUrl(dto.directUrlOrFalse());
        cfg.setConfig(dto.configJson());
        cfg.setLastUpdate(OffsetDateTime.now());
        return ContentProviderConfigView.of(repository.save(cfg));
    }

    @Transactional(readOnly = true)
    public ContentProviderConfigView current() {
        return ContentProviderConfigView.of(forCurrentTenant()
                .orElseThrow(() -> NotFoundException.forResource("ContentProviderConfig", tenantScope.currentTenantId())));
    }

    @Transactional
    public void deleteForCurrentTenant() {
        repository.findByTenantId(tenantScope.currentTenantId()).ifPresent(repository::delete);
    }



}
