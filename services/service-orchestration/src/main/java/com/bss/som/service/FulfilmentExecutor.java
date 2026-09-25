package com.bss.som.service;

import com.bss.som.client.CatalogClient;
import com.bss.som.client.CatalogClient.Rfs;
import com.bss.som.seam.FulfilmentPlan;
import com.bss.som.seam.FulfilmentPlan.PlannedSeam;
import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamRegistry;
import com.bss.som.seam.SeamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the orchestrator decides fulfilment from the catalog
 * (CONTEXT.md: executor). It walks the resource-facing services the CFS
 * declares in the fixed seam order, skips the optional ones the product does
 * not call for, hands each seam adapter the values the CFS→RFS edge says the
 * RFS consumes, and records a realisation for every seam it ran. It is the
 * only reader of the product specification.
 */
@Service
public class FulfilmentExecutor {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentExecutor.class);

    /** The vendor recorded when the catalog declares a seam no adapter serves in this fleet. */
    public static final String NO_ADAPTER = "no-adapter";

    private final SeamRegistry registry;
    private final CatalogClient catalog;
    private final LineProvisioning lines;

    public FulfilmentExecutor(SeamRegistry registry, CatalogClient catalog, LineProvisioning lines) {
        this.registry = registry;
        this.catalog = catalog;
        this.lines = lines;
    }

    /**
     * @param plan           what was decided, seam by seam
     * @param ran            the seams that were exercised, in order
     * @param numbered       a pool-drawing seam ({@code number} or {@code edge-gpu}) ran
     * @param lineNumbered   the {@code number} seam ran — the line has (or should have) an MSISDN
     * @param clearsDeferral a seam stood the line up so the item need not wait for an install
     */
    public record Outcome(FulfilmentPlan plan, List<String> ran, boolean numbered, boolean lineNumbered,
            boolean clearsDeferral) {
    }

    /**
     * The consumed values for this order: the edge names them; the order item's
     * product characteristics win over the product specification's.
     */
    public Map<String, String> consumedValues(List<Rfs> declared, String offeringId, Map<String, Object> item) {
        Map<String, String> fromItem = new HashMap<>();
        if (item != null && item.get("product") instanceof Map<?, ?> product
                && product.get("productCharacteristic") instanceof List<?> chars) {
            for (Object c : chars) {
                if (c instanceof Map<?, ?> ch && ch.get("name") != null && ch.get("value") != null) {
                    fromItem.putIfAbsent(String.valueOf(ch.get("name")), String.valueOf(ch.get("value")));
                }
            }
        }
        return FulfilmentPlan.consumedValues(declared, catalog.specCharacteristicsOf(offeringId), fromItem);
    }

    /** Plan without running anything — the dry run reads this. */
    public FulfilmentPlan plan(List<Rfs> declared, SeamContext ctx) {
        return FulfilmentPlan.of(declared, registry, ctx);
    }

    /** Run the plan for one order item, recording a realisation for every seam exercised. */
    public Outcome execute(List<Rfs> declared, SeamContext ctx) {
        FulfilmentPlan plan = plan(declared, ctx);
        List<String> ran = new ArrayList<>();
        boolean numbered = false;
        boolean lineNumbered = false;
        boolean clears = false;
        for (PlannedSeam p : plan.seams()) {
            switch (p.verdict()) {
                case NO_ADAPTER -> {
                    log.warn("service {}: CFS declares seam '{}' ({}) but no adapter serves it in this fleet",
                            ctx.serviceId(), p.seam(), p.rfs().name());
                    lines.realise(ctx.tenant(), ctx.serviceId(), ctx.offeringId(), p.seam(), NO_ADAPTER, null);
                }
                case SKIP_OPTIONAL, SKIP_PRECONDITION ->
                    log.debug("service {}: seam '{}' not run — {}", ctx.serviceId(), p.seam(), p.why());
                case RUN -> {
                    SeamAdapter adapter = registry.forSeam(p.seam()).orElseThrow();
                    SeamResult result = adapter.run(ctx);
                    if (!result.realised()) {
                        log.info("service {}: seam '{}' had nothing to do — {}", ctx.serviceId(), p.seam(), result.note());
                        continue;
                    }
                    ran.add(p.seam());
                    lines.realise(ctx.tenant(), ctx.serviceId(), ctx.offeringId(), p.seam(), result.vendor(), result.externalRef());
                    if ("number".equals(p.seam()) || "edge-gpu".equals(p.seam())) {
                        numbered = true;
                    }
                    if ("number".equals(p.seam())) {
                        lineNumbered = true;
                    }
                    if (result.clearsDeferral()) {
                        clears = true;
                    }
                }
            }
        }
        return new Outcome(plan, List.copyOf(ran), numbered, lineNumbered, clears);
    }
}
