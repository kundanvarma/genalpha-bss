package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Seam {@code ocs}: the subscriber and its counters are provisioned on the
 * operator's online charging system — the OCS stays the charging master. The
 * rate plan ({@code chargingSpecId}) and the zero-rated apps come from the
 * consumed values the executor read off the catalog; the overage tiers are the
 * usage-allowance service's own fact about the offering.
 */
@Component
public class OcsSeam implements SeamAdapter {

    private final com.bss.som.client.OcsProvisioningClient ocs;
    private final com.bss.som.client.UsageAllowanceClient usageAllowances;

    public OcsSeam(com.bss.som.client.OcsProvisioningClient ocs, com.bss.som.client.UsageAllowanceClient usageAllowances) {
        this.ocs = ocs;
        this.usageAllowances = usageAllowances;
    }

    @Override
    public String seam() {
        return "ocs";
    }

    @Override
    public Set<String> consumes() {
        return Set.of("chargingSpecId", "zeroRatedApps", "overageTier");
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.ofNullable(ocs.vendor(tenantId));
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        String chargingSpec = ctx.value("chargingSpecId");
        if (chargingSpec == null || chargingSpec.isBlank()) {
            return SeamResult.skipped("the product names no charging plan");
        }
        chargingSpec = chargingSpec.trim();
        List<String> zeroRated = new ArrayList<>();
        String apps = ctx.value("zeroRatedApps");
        if (apps != null) {
            for (String a : apps.split(",")) {
                if (!a.isBlank()) {
                    zeroRated.add(a.trim());
                }
            }
        }
        // zero-rated apps ride along: the OCS, not the BSS, makes them free
        ocs.provision(ctx.tenant(), ctx.owner(), ctx.serviceId(), chargingSpec, zeroRated);
        // the BSS-defined overage steps ride to the charging system, so real-time and bill-time agree
        ocs.pushOverageTiers(ctx.tenant(), ctx.serviceId(), chargingSpec, usageAllowances.tiersOf(ctx.offeringId()));
        return SeamResult.realised(ocs.vendor(ctx.tenant()), chargingSpec);
    }
}
