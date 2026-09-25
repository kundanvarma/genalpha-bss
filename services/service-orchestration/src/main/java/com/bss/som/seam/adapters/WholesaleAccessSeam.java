package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.LineProvisioning;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Seam {@code wholesale-access}: a broadband line delivered over a third-party
 * owner's fibre (MEF Sonata). ENVIRONMENT-GATED: it applies when the item
 * carries an install address; whether an owner serves it is the adapter's own
 * finding, and "our own network here" is a skip, not a failure.
 */
@Component
public class WholesaleAccessSeam implements SeamAdapter {

    private final LineProvisioning lines;
    private final com.bss.som.client.WholesaleAccessClient wholesaleAccess;

    public WholesaleAccessSeam(LineProvisioning lines, com.bss.som.client.WholesaleAccessClient wholesaleAccess) {
        this.lines = lines;
        this.wholesaleAccess = wholesaleAccess;
    }

    @Override
    public String seam() {
        return "wholesale-access";
    }

    @Override
    public boolean environmentGated() {
        return true;
    }

    @Override
    public boolean appliesTo(SeamContext ctx) {
        String postCode = LineProvisioning.postCodeOf(ctx.item());
        return postCode != null && !postCode.isBlank();
    }

    @Override
    public Set<String> consumes() {
        return Set.of("accessLayer", "speed", "downloadSpeed", "accessOwner");
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.ofNullable(wholesaleAccess.vendor());
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        return lines.placeWholesaleAccess(ctx.tenant(), ctx.item(), ctx.serviceId(), ctx.owner(), ctx.productOrderId())
                .map(p -> p.active()
                        ? SeamResult.realisedAndUp(wholesaleAccess.vendor(), p.externalId())
                        : SeamResult.realised(wholesaleAccess.vendor(), p.externalId()))
                .orElseGet(() -> SeamResult.skipped("no access owner serves this address — our own network"));
    }
}
