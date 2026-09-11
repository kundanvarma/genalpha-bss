package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The OCS seam as the orchestrator sees it: one client, routed per tenant.
 * Provider choice is a tenant-fleet-file fact (tenants.yml {@code ocs-provider}),
 * not a build flag — one deployment can charge tenant A on the bundled mock,
 * tenant B on SigScale OCS and tenant C on a vendor gateway. An adapter name
 * nobody registered is a logged no-op: charging never blocks activation.
 */
@Primary
@Component
public class TenantOcsRouter implements OcsProvisioningClient {

    private static final Logger log = LoggerFactory.getLogger(TenantOcsRouter.class);

    private final Map<String, OcsProviderAdapter> adapters;
    private final OcsSettings settings;

    public TenantOcsRouter(List<OcsProviderAdapter> adapters, OcsSettings settings) {
        this.adapters = adapters.stream().collect(
                Collectors.toMap(OcsProviderAdapter::name, Function.identity()));
        this.settings = settings;
    }

    /** The adapter serving this tenant, or null when none applies. */
    public OcsProviderAdapter adapterFor(String tenantId) {
        String provider = settings.forTenant(tenantId).provider();
        OcsProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            log.warn("OCS: tenant {} names provider '{}' but no such adapter is on the classpath ({}) — charging not mirrored",
                    tenantId, provider, adapters.keySet());
        }
        return adapter;
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId) {
        OcsProviderAdapter a = adapterFor(tenantId);
        if (a != null) {
            a.provision(tenantId, partyId, serviceId, chargingSpecId);
        }
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId,
            List<String> zeroRatedApps) {
        OcsProviderAdapter a = adapterFor(tenantId);
        if (a != null) {
            a.provision(tenantId, partyId, serviceId, chargingSpecId, zeroRatedApps);
        }
    }

    @Override
    public void changeRatePlan(String tenantId, String serviceId, String chargingSpecId) {
        OcsProviderAdapter a = adapterFor(tenantId);
        if (a != null) {
            a.changeRatePlan(tenantId, serviceId, chargingSpecId);
        }
    }

    @Override
    public void suspend(String tenantId, String serviceId) {
        OcsProviderAdapter a = adapterFor(tenantId);
        if (a != null) {
            a.suspend(tenantId, serviceId);
        }
    }

    @Override
    public void resume(String tenantId, String serviceId) {
        OcsProviderAdapter a = adapterFor(tenantId);
        if (a != null) {
            a.resume(tenantId, serviceId);
        }
    }

    @Override
    public void transfer(String tenantId, String serviceId, String newPartyId) {
        OcsProviderAdapter a = adapterFor(tenantId);
        if (a != null) {
            a.transfer(tenantId, serviceId, newPartyId);
        }
    }
}
