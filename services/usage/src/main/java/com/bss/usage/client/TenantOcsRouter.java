package com.bss.usage.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The OCS seam as the TMF654 facade sees it: one client, routed per tenant
 * by tenants.yml {@code ocs-provider}. An adapter name nobody registered
 * answers like "no OCS" — empty balances, honest message.
 */
@Primary
@Component
public class TenantOcsRouter implements OcsClient {

    private static final Logger log = LoggerFactory.getLogger(TenantOcsRouter.class);

    private final Map<String, OcsBalanceAdapter> adapters;
    private final OcsSettings settings;

    public TenantOcsRouter(List<OcsBalanceAdapter> adapters, OcsSettings settings) {
        this.adapters = adapters.stream().collect(
                Collectors.toMap(OcsBalanceAdapter::name, Function.identity()));
        this.settings = settings;
    }

    public OcsBalanceAdapter adapterFor(String tenantId) {
        String provider = settings.forTenant(tenantId).provider();
        OcsBalanceAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            log.warn("OCS: tenant {} names provider '{}' but no such adapter is on the classpath ({})",
                    tenantId, provider, adapters.keySet());
        }
        return adapter;
    }

    @Override
    public boolean enabled(String tenantId) {
        OcsBalanceAdapter a = adapterFor(tenantId);
        return a != null && a.enabled(tenantId);
    }

    @Override
    public List<Map<String, Object>> subscribersOf(String tenantId, String partyId) {
        OcsBalanceAdapter a = adapterFor(tenantId);
        return a == null ? List.of() : a.subscribersOf(tenantId, partyId);
    }

    @Override
    public boolean credit(String tenantId, String subscriberId, double gb) {
        OcsBalanceAdapter a = adapterFor(tenantId);
        return a != null && a.credit(tenantId, subscriberId, gb);
    }
}
