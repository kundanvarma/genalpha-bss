package com.bss.som.client;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The partner seam as the orchestrator sees it: one client, routed per tenant.
 * Which platform is a tenant-fleet-file fact, not a build flag — one
 * deployment can activate tenant A's partner services in-process and tenant
 * B's through a partner's REST gateway. A platform nobody registered is a
 * refusal with a reason, never a silent success.
 */
@Primary
@Component
public class TenantPartnerRouter implements PartnerEntitlementClient {

    private final Map<String, PartnerEntitlementClient> adapters;
    private final PartnerSettings settings;

    public TenantPartnerRouter(List<PartnerPlatform> adapters, PartnerSettings settings) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(PartnerEntitlementClient::name, Function.<PartnerEntitlementClient>identity()));
        this.settings = settings;
    }

    @Override
    public String name() {
        return "router";
    }

    private PartnerEntitlementClient adapterFor(String tenantId) {
        return adapters.get(settings.forTenant(tenantId).platform());
    }

    @Override
    public String vendor(String tenantId) {
        PartnerEntitlementClient a = adapterFor(tenantId);
        return a == null ? "none (" + settings.forTenant(tenantId).platform() + " is not installed)" : a.vendor(tenantId);
    }

    @Override
    public Activation activate(String tenantId, String requestKey, String offeringName, String customerPartyId) {
        PartnerEntitlementClient a = adapterFor(tenantId);
        return a == null
                ? Activation.refused("no partner platform '" + settings.forTenant(tenantId).platform() + "' is installed")
                : a.activate(tenantId, requestKey, offeringName, customerPartyId);
    }

    @Override
    public Activation status(String tenantId, String requestKey) {
        PartnerEntitlementClient a = adapterFor(tenantId);
        return a == null ? Activation.unknown("no partner platform") : a.status(tenantId, requestKey);
    }

    @Override
    public void deactivate(String tenantId, String requestKey, String activationCode, String customerPartyId) {
        PartnerEntitlementClient a = adapterFor(tenantId);
        if (a == null) {
            throw new IllegalStateException("no partner platform installed for tenant " + tenantId);
        }
        a.deactivate(tenantId, requestKey, activationCode, customerPartyId);
    }
}
