package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.LineProvisioning;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** Seam {@code partner-entitlement}: the partner's platform owns the account; we hold the activation code. */
@Component
public class PartnerEntitlementSeam implements SeamAdapter {

    private final LineProvisioning lines;
    private final com.bss.som.client.PartnerEntitlementClient partners;

    public PartnerEntitlementSeam(LineProvisioning lines, com.bss.som.client.PartnerEntitlementClient partners) {
        this.lines = lines;
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
        return Optional.ofNullable(partners.vendor());
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        String code = partners.activate(ctx.offeringName(), ctx.owner());
        lines.assignPartnerCode(ctx.tenant(), ctx.serviceId(), ctx.owner(), code);
        return SeamResult.realised(partners.vendor(), code);
    }
}
