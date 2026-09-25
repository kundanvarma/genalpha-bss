package com.bss.som.seam.adapters;

import com.bss.som.client.CatalogClient.SliceIntent;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.LineProvisioning;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Seam {@code slice}: a PRIORITY TIER — the plan itself names a slice profile,
 * so the line rides it for as long as the plan does (no expiry), and moves to
 * the slice rate plan when the product names one.
 */
@Component
public class SliceSeam implements SeamAdapter {

    private final com.bss.som.client.SliceProvisioningClient slices;
    private final ServiceInstanceRepository services;
    private final LineProvisioning lines;

    public SliceSeam(com.bss.som.client.SliceProvisioningClient slices, ServiceInstanceRepository services,
            LineProvisioning lines) {
        this.slices = slices;
        this.services = services;
        this.lines = lines;
    }

    @Override
    public String seam() {
        return "slice";
    }

    @Override
    public Set<String> consumes() {
        return Set.of("sliceProfile", "boostHours", "sliceChargingSpecId", "guaranteedDlMbps", "deliveryPath");
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.ofNullable(slices.vendor());
    }

    /** The slice intent as the consumed values spell it — the same four names the product spec carries. */
    public static Optional<SliceIntent> intentOf(SeamContext ctx) {
        String profile = ctx.value("sliceProfile");
        if (profile == null || profile.isBlank()) {
            return Optional.empty();
        }
        String chargingSpec = ctx.value("sliceChargingSpecId");
        return Optional.of(new SliceIntent(profile, ctx.intValue("boostHours"),
                chargingSpec == null ? null : chargingSpec.trim(), ctx.intValue("guaranteedDlMbps")));
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        Optional<SliceIntent> intent = intentOf(ctx);
        if (intent.isEmpty()) {
            return SeamResult.skipped("the product names no slice profile");
        }
        SliceIntent it = intent.get();
        services.findById(ctx.serviceId()).ifPresent(line -> {
            line.setSliceProfile(it.profile());
            line.setSliceUntil(null);
            line.setSliceOrderId(ctx.productOrderId());
            line.setSliceGuaranteedDlMbps(it.guaranteedDlMbps());
            services.save(line);
            lines.moveToSliceChargingPlan(ctx.tenant(), line, it.chargingSpecId());
        });
        slices.apply(ctx.tenant(), ctx.serviceId(), it.profile(), null);
        return SeamResult.realised(slices.vendor(), it.profile());
    }
}
