package com.bss.address.service;

import com.bss.address.entity.RegistryConfig;
import com.bss.address.repository.RegistryConfigRepository;
import com.bss.address.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public List<Map<String, Object>> listForCurrentTenant() {
        return repository.findByTenantIdOrderByCountryAsc(tenantScope.currentTenantId())
                .stream().map(RegistryConfigService::toMap).toList();
    }

    @Transactional
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String provider = str(dto.get("provider"));
        if (provider == null || !KNOWN.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider is required and must be one of " + KNOWN);
        }
        String country = str(dto.get("country"));
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
        cfg.setDisplayName(str(dto.getOrDefault("displayName", provider)));
        cfg.setBaseUrl(str(dto.get("baseUrl")));
        cfg.setSecretRef(str(dto.get("secretRef")));
        cfg.setEnabled(!Boolean.FALSE.equals(dto.get("enabled")));   // default true
        cfg.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(cfg));
    }

    @Transactional
    public void delete(String country) {
        repository.findByTenantIdAndCountry(tenantScope.currentTenantId(),
                country.toUpperCase(Locale.ROOT)).ifPresent(repository::delete);
    }

    /** Test connection: reachability of the configured base URL's /health —
     * never a person lookup. */
    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(String country) {
        RegistryConfig cfg = repository.findByTenantIdAndCountry(tenantScope.currentTenantId(),
                country.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no registry configured for country '" + country + "'"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", cfg.getCountry());
        out.put("provider", cfg.getProvider());
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            out.put("ok", true);
            out.put("note", "no base URL configured — nothing to probe");
            return out;
        }
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpResponse<Void> resp = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.getBaseUrl() + "/health"))
                            .timeout(java.time.Duration.ofSeconds(4)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            out.put("ok", resp.statusCode() < 500);
            out.put("status", resp.statusCode());
            out.put("note", "reachability probe of " + cfg.getBaseUrl() + "/health — never a person lookup");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("note", "unreachable: " + e.getMessage());
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** The secret is a reference only — the credential is never returned. */
    private static Map<String, Object> toMap(RegistryConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("country", c.getCountry());
        m.put("provider", c.getProvider());
        m.put("displayName", c.getDisplayName());
        if (c.getBaseUrl() != null) m.put("baseUrl", c.getBaseUrl());
        if (c.getSecretRef() != null) m.put("secretRef", c.getSecretRef());
        m.put("enabled", c.isEnabled());
        m.put("@type", "RegistryConfig");
        return m;
    }
}
