package com.bss.fulfilment.service;

import com.bss.fulfilment.entity.CarrierConfig;
import com.bss.fulfilment.repository.CarrierConfigRepository;
import com.bss.fulfilment.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's carrier menu — CRUD over per-tenant carrier bindings, plus the
 * routing lookups the fulfilment booking path uses. RLS scopes every row.
 */
@Service
public class CarrierConfigService {

    private static final Set<String> KNOWN = Set.of("helthjem", "bring");

    private final CarrierConfigRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public CarrierConfigService(CarrierConfigRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForCurrentTenant() {
        return repository.findByTenantIdOrderByDisplayNameAsc(tenantScope.currentTenantId())
                .stream().map(CarrierConfigService::toMap).toList();
    }

    /** The carrier a booking uses when the shopper hasn't picked one (C-P3): the
     * default-flagged enabled carrier, else the first enabled — or empty (global fallback). */
    @Transactional(readOnly = true)
    public Optional<CarrierConfig> defaultForTenant(String tenant) {
        List<CarrierConfig> enabled = repository.findByTenantIdAndEnabledTrue(tenant);
        return enabled.stream().min(Comparator
                .comparing((CarrierConfig c) -> !c.isDefault())        // default first
                .thenComparing(c -> String.valueOf(c.getDisplayName())));
    }

    @Transactional(readOnly = true)
    public Optional<CarrierConfig> forTenantAndCarrier(String tenant, String carrier) {
        return repository.findByTenantIdAndCarrier(tenant, carrier);
    }

    @Transactional
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String carrier = str(dto.get("carrier"));
        if (carrier == null || !KNOWN.contains(carrier)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "carrier is required and must be one of " + KNOWN);
        }
        String tenant = tenantScope.currentTenantId();
        CarrierConfig cfg = repository.findByTenantIdAndCarrier(tenant, carrier).orElseGet(() -> {
            CarrierConfig fresh = new CarrierConfig();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenant);
            fresh.setCarrier(carrier);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        cfg.setDisplayName(str(dto.getOrDefault("displayName", carrier)));
        cfg.setBaseUrl(str(dto.get("baseUrl")));
        cfg.setSecretRef(str(dto.get("secretRef")));
        cfg.setMethods(json(dto.get("methods")));
        cfg.setConfig(json(dto.get("config")));
        cfg.setDefault(Boolean.TRUE.equals(dto.get("isDefault")));
        cfg.setEnabled(!Boolean.FALSE.equals(dto.get("enabled")));   // default true
        cfg.setLastUpdate(OffsetDateTime.now());
        // only one default per tenant
        if (cfg.isDefault()) {
            for (CarrierConfig other : repository.findByTenantIdOrderByDisplayNameAsc(tenant)) {
                if (!other.getId().equals(cfg.getId()) && other.isDefault()) {
                    other.setDefault(false);
                    repository.save(other);
                }
            }
        }
        return toMap(repository.save(cfg));
    }

    @Transactional
    public void delete(String carrier) {
        repository.findByTenantIdAndCarrier(tenantScope.currentTenantId(), carrier)
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
    private static Map<String, Object> toMap(CarrierConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("carrier", c.getCarrier());
        m.put("displayName", c.getDisplayName());
        if (c.getBaseUrl() != null) m.put("baseUrl", c.getBaseUrl());
        if (c.getSecretRef() != null) m.put("secretRef", c.getSecretRef());
        if (c.getMethods() != null) m.put("methods", c.getMethods());
        m.put("isDefault", c.isDefault());
        m.put("enabled", c.isEnabled());
        m.put("@type", "CarrierConfig");
        return m;
    }
}
