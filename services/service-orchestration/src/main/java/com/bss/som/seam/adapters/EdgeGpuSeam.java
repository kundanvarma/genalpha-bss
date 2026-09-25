package com.bss.som.seam.adapters;

import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.LineProvisioning;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** Seam {@code edge-gpu}: a compute product draws a GPU from the edge pool instead of a number. */
@Component
public class EdgeGpuSeam implements SeamAdapter {

    public static final String POOL_TYPE = "edge-gpu";

    private final LineProvisioning lines;

    public EdgeGpuSeam(LineProvisioning lines) {
        this.lines = lines;
    }

    @Override
    public String seam() {
        return POOL_TYPE;
    }

    @Override
    public Set<String> consumes() {
        return Set.of();
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.of("own-pool");
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        return lines.drawFromPool(ctx.tenant(), POOL_TYPE, ctx.serviceId(), ctx.owner(), null)
                .map(value -> SeamResult.realised("own-pool", value))
                .orElseGet(() -> SeamResult.skipped("the tenant has no edge-gpu pool"));
    }
}
