package com.bss.fulfilment.service;

import com.bss.fulfilment.dto.CarrierConfigRequest;
import com.bss.fulfilment.dto.CarrierConfigView;
import com.bss.fulfilment.dto.CarrierProbe;
import com.bss.fulfilment.dto.Json;
import com.bss.fulfilment.entity.CarrierConfig;
import com.bss.fulfilment.repository.CarrierConfigRepository;
import com.bss.fulfilment.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's carrier menu — CRUD over per-tenant carrier bindings, plus the
 * routing lookups the fulfilment booking path uses. RLS scopes every row.
 */
@Service
public class CarrierConfigService {

    private static final Set<String> KNOWN = Set.of("helthjem", "bring", "postnord", "http");

    private final CarrierConfigRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public CarrierConfigService(CarrierConfigRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<CarrierConfigView> listForCurrentTenant() {
        return repository.findByTenantIdOrderByDisplayNameAsc(tenantScope.currentTenantId())
                .stream().map(CarrierConfigService::toView).toList();
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

    @Transactional(readOnly = true)
    public List<CarrierConfig> enabledForTenant(String tenant) {
        return repository.findByTenantIdAndEnabledTrue(tenant);
    }

    /** Rule-based routing: the enabled carrier whose postcode prefix best matches
     * (longest wins), or empty when no rule applies. */
    @Transactional(readOnly = true)
    public Optional<CarrierConfig> routeByPostcode(String tenant, String postcode) {
        if (postcode == null || postcode.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTenantIdAndEnabledTrue(tenant).stream()
                .filter(c -> c.getPostcodePrefix() != null && !c.getPostcodePrefix().isBlank()
                        && postcode.startsWith(c.getPostcodePrefix()))
                .max(Comparator.comparingInt(c -> c.getPostcodePrefix().length()));
    }

    @Transactional
    public CarrierConfigView upsert(CarrierConfigRequest body) {
        CarrierConfigRequest dto = body == null ? CarrierConfigRequest.EMPTY : body;
        String carrier = dto.carrierKey();
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
        cfg.setDisplayName(dto.displayNameOr(carrier));
        cfg.setBaseUrl(Json.textOrNull(dto.baseUrl()));
        cfg.setSecretRef(Json.textOrNull(dto.secretRef()));
        cfg.setMethods(json(dto.methods()));
        cfg.setConfig(json(dto.config()));
        cfg.setPostcodePrefix(Json.textOrNull(dto.postcodePrefix()));
        cfg.setDefault(dto.makeDefault());
        cfg.setEnabled(dto.stayEnabled());                           // default true
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
        return toView(repository.save(cfg));
    }

    @Transactional
    public void delete(String carrier) {
        repository.findByTenantIdAndCarrier(tenantScope.currentTenantId(), carrier)
                .ifPresent(repository::delete);
    }

    /** Test connection: reachability of the configured base URL's /health with a
     * short timeout — never a booking. No base URL = nothing to probe, said so. */
    @Transactional(readOnly = true)
    public CarrierProbe testConnection(String carrier) {
        CarrierConfig cfg = repository.findByTenantIdAndCarrier(tenantScope.currentTenantId(), carrier)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "carrier '" + carrier + "' is not configured for this tenant"));
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return CarrierProbe.nothingToProbe(carrier);
        }
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpResponse<Void> resp = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(cfg.getBaseUrl() + "/health"))
                            .timeout(java.time.Duration.ofSeconds(4)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return CarrierProbe.reached(carrier, resp.statusCode(), cfg.getBaseUrl());
        } catch (Exception e) {
            return CarrierProbe.unreachable(carrier, e.getMessage());
        }
    }

    /** A posted string is stored as it came; anything else is stored as its own JSON. */
    private String json(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.textValue();
        }
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not serialisable JSON");
        }
    }

    /** The secret is a reference only — the API key is never returned. */
    private static CarrierConfigView toView(CarrierConfig c) {
        return new CarrierConfigView(c.getCarrier(), c.getDisplayName(), c.getBaseUrl(),
                c.getSecretRef(), c.getMethods(), c.getPostcodePrefix(), c.isDefault(), c.isEnabled());
    }
}
