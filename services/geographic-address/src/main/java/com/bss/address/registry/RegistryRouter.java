package com.bss.address.registry;

import com.bss.address.entity.RegistryConfig;
import com.bss.address.entity.RegistryLookupLog;
import com.bss.address.repository.RegistryConfigRepository;
import com.bss.address.repository.RegistryLookupLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolution: delivery country → the tenant's registry_config row for that
 * country → adapter. No row (or no adapter for its provider) = no registry
 * for that market — the caller degrades to postal wash and the answer says
 * 'unavailable' rather than pretending. EVERY lookup that reaches a registry
 * is written to the GDPR ledger, outcome only.
 */
@Component
public class RegistryRouter {

    public static final String PURPOSE_DELIVERY = "delivery-address-verification";

    private final RegistryConfigRepository configs;
    private final RegistryRegistry registry;
    private final RegistryLookupLogRepository lookupLog;

    public RegistryRouter(RegistryConfigRepository configs, RegistryRegistry registry,
            RegistryLookupLogRepository lookupLog) {
        this.configs = configs;
        this.registry = registry;
        this.lookupLog = lookupLog;
    }

    /**
     * Match a person against the claimed address in the given country, or
     * empty when the tenant has no enabled registry there. The result map is
     * the wire-ready {@code registryMatch} part.
     */
    @Transactional
    public Optional<Map<String, Object>> match(String tenantId, String country,
            RegistryAdapter.Person person, Map<String, Object> address, String callerSub) {
        Optional<RegistryConfig> bound = configs.findByTenantIdAndCountry(tenantId, country)
                .filter(RegistryConfig::isEnabled);
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        RegistryConfig cfg = bound.get();
        RegistryAdapter adapter = registry.get(cfg.getProvider());
        if (adapter == null) {
            return Optional.empty();
        }
        RegistryAdapter.Result result = adapter.match(cfg, person, address);

        RegistryLookupLog row = new RegistryLookupLog();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantId);
        row.setCountry(country);
        row.setProvider(cfg.getProvider());
        row.setCallerSub(callerSub);
        row.setPartyName(person.name());
        row.setPurpose(PURPOSE_DELIVERY);
        row.setOutcome(result.outcome());
        row.setCreatedAt(OffsetDateTime.now());
        lookupLog.save(row);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", cfg.getProvider());
        out.put("country", country);
        out.put("outcome", result.outcome());
        if (result.registeredAddress() != null) {
            out.put("registeredAddress", result.registeredAddress());
        }
        if (result.movedDate() != null) {
            out.put("movedDate", result.movedDate());
        }
        return Optional.of(out);
    }
}
