package com.bss.som.seam.adapters;

import com.bss.som.entity.ResourcePool;
import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.LineProvisioning;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Seam {@code number}: the line's MSISDN. Keep-your-number first (a ported-in
 * number becomes the assignment and no pool is drawn), else the next free
 * number from the tenant's pool, honouring the shopper's wish while it is free.
 */
@Component
public class NumberSeam implements SeamAdapter {

    private final LineProvisioning lines;
    private final com.bss.som.client.PortingClient porting;

    public NumberSeam(LineProvisioning lines, com.bss.som.client.PortingClient porting) {
        this.lines = lines;
        this.porting = porting;
    }

    @Override
    public String seam() {
        return "number";
    }

    @Override
    public Set<String> consumes() {
        return Set.of("msisdn");
    }

    @Override
    public Optional<String> vendor(String tenantId) {
        return Optional.of("own-pool");
    }

    @Override
    public SeamResult run(SeamContext ctx) {
        String ported = porting.portedNumberFor(ctx.owner());
        if (ported != null) {
            lines.assignPorted(ctx.tenant(), ctx.serviceId(), ctx.owner(), ported);
            return SeamResult.realised("ported-in", ported);
        }
        String wish = ctx.wishNumber() != null ? ctx.wishNumber() : ctx.value("msisdn");
        return lines.drawFromPool(ctx.tenant(), ResourcePool.MSISDN, ctx.serviceId(), ctx.owner(), wish)
                .map(value -> SeamResult.realised("own-pool", value))
                .orElseGet(() -> SeamResult.skipped("the tenant has no number pool"));
    }
}
