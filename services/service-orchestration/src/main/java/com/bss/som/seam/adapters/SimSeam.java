package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.LineProvisioning;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Seam {@code sim}: the line's SIM — a starter kit's card when the order rides
 * one, else minted operator-side with its PUK. The PIN lives on the card.
 */
@Component
public class SimSeam implements SeamAdapter {

    public static final String VENDOR = "house-sim-issuer";

    private final LineProvisioning lines;

    public SimSeam(LineProvisioning lines) {
        this.lines = lines;
    }

    @Override
    public String seam() {
        return "sim";
    }

    @Override
    public Set<String> consumes() {
        return Set.of("simType", "eid");
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.of(VENDOR);
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        lines.attachKitSimOrMint(ctx.tenant(), ctx.serviceId(), ctx.productOrderId());
        return SeamResult.realised(VENDOR, lines.iccidOf(ctx.tenant(), ctx.serviceId()).orElse(null));
    }
}
