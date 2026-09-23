package com.bss.address.service;

import com.bss.address.dto.RegistryConfigRequest;
import com.bss.address.dto.RegistryConfigView;
import com.bss.address.dto.RegistryTestResult;
import com.bss.address.entity.RegistryConfig;
import com.bss.address.repository.RegistryConfigRepository;
import com.bss.address.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's registry bindings — CRUD over per-(tenant, country) national
 * registries. One binding per country; the credential is a secret-ref (env-var
 * name), never the value. NO named-adapter escape hatch here: registries carry
 * national-ID PII and a per-country legal basis, so 'http'-style generic
 * connection is deliberately excluded (same doctrine that keeps money off the
 * generic carrier seam).
 */
@Service
public class RegistryConfigService {

    private static final Set<String> KNOWN = Set.of("freg");

    private final RegistryConfigRepository repository;
    private final TenantScope tenantScope;

    public RegistryConfigService(RegistryConfigRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<RegistryConfigView> listForCurrentTenant() {
        return repository.findByTenantIdOrderByCountryAsc(tenantScope.currentTenantId())
                .stream().map(RegistryConfigView::of).toList();
    }

    @Transactional
    public RegistryConfigView upsert(RegistryConfigRequest dto) {
        String provider = dto.provider();
        if (provider == null || !KNOWN.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider is required and must be one of " + KNOWN);
        }
        String country = dto.country();
        if (country == null || country.length() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "country is required (ISO 3166-1 alpha-2)");
        }
        String iso = country.toUpperCase(Locale.ROOT);
        String tenant = tenantScope.currentTenantId();
        RegistryConfig cfg = repository.findByTenantIdAndCountry(tenant, iso).orElseGet(() -> {
            RegistryConfig fresh = new RegistryConfig();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenant);
            fresh.setCountry(iso);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        cfg.setProvider(provider);
        cfg.setDisplayName(dto.displayName() == null ? provider : dto.displayName());
        cfg.setBaseUrl(dto.baseUrl());
        cfg.setSecretRef(dto.secretRef());
        cfg.setEnabled(dto.enabledOrDefault());   // default true
        cfg.setLastUpdate(OffsetDateTime.now());
        return RegistryConfigView.of(repository.save(cfg));
    }

    @Transactional
    public void delete(String country) {
        repository.findByTenantIdAndCountry(tenantScope.currentTenantId(),
                country.toUpperCase(Locale.ROOT)).ifPresent(repository::delete);
    }

    /** Test connection: reachability of the configured base URL's /health —
     * never a person lookup. */
    @Transactional(readOnly = true)
    public RegistryTestResult testConnection(String country) {
        RegistryConfig cfg = repository.findByTenantIdAndCountry(tenantScope.currentTenantId(),
                country.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no registry configured for country '" + country + "'"));
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return RegistryTestResult.nothingToProbe(cfg.getCountry(), cfg.getProvider());
        }
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpResponse<Void> resp = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.getBaseUrl() + "/health"))
                            .timeout(java.time.Duration.ofSeconds(4)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return RegistryTestResult.probed(cfg.getCountry(), cfg.getProvider(),
                    resp.statusCode(), cfg.getBaseUrl());
        } catch (Exception e) {
            return RegistryTestResult.unreachable(cfg.getCountry(), cfg.getProvider(),
                    e.getMessage());
        }
    }

}
