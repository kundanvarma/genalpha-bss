package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** Seam {@code partner-entitlement}: the partner's platform owns the account; we hold the activation code once it confirms (#355). */
@Component
public class PartnerEntitlementSeam implements SeamAdapter {

    private final com.bss.som.service.PartnerActivations activations;
    private final com.bss.som.client.PartnerEntitlementClient partners;

    public PartnerEntitlementSeam(com.bss.som.service.PartnerActivations activations,
            com.bss.som.client.PartnerEntitlementClient partners) {
        this.activations = activations;
        this.partners = partners;
    }

    @Override
    public String seam() {
        return "partner-entitlement";
    }

    @Override
    public Set<String> consumes() {
        return Set.of();
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.ofNullable(partners.vendor(tenantId));
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        // pending or refused is settled by the order loop and the sweep (PartnerActivations)
        Object itemId = ctx.item() == null ? null : ctx.item().get("id");
        com.bss.som.service.PartnerActivations.Started started = activations.start(ctx.tenant(), ctx.serviceId(),
                ctx.owner(), ctx.offeringName(), ctx.productOrderId(), itemId == null ? null : itemId.toString());
        return SeamResult.realised(partners.vendor(ctx.tenant()),
                started.code() != null ? started.code() : started.state().name().toLowerCase());
    }
}
