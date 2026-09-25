package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The executor's plan for an offering with no adapter called (CONTEXT.md: dry
 * run). Every field is a fact the executor would act on; {@code summary} says
 * the same in operator language so the offering page and the launch-readiness
 * item speak one sentence per step.
 *
 * @param offeringId           the offering planned for
 * @param offeringName         its name
 * @param cfs                  the customer-facing service the product spec names; null on the fallback
 * @param fallback             true when no CFS is named (or the RFS list was unreadable): the category table decides
 * @param category             the offering's category, the fallback's only input
 * @param steps                one per declared seam, in the order the executor runs them
 * @param createsServiceRecord whether an order would stand up a service record
 * @param drawsFromPool        whether a pool-drawing seam (number, edge-gpu) would run
 * @param verdict              LAUNCHABLE · NOT_LAUNCHABLE_HERE (a required seam has no adapter) · FALLBACK
 * @param reason               the verdict in one sentence
 * @param summary              the plan in words, one line per step, then the verdict
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"offeringId", "offeringName", "cfs", "fallback", "category", "steps", "createsServiceRecord",
        "drawsFromPool", "verdict", "reason", "summary"})
public record DryRunPlan(String offeringId, String offeringName, CfsRef cfs, boolean fallback, String category,
        List<Step> steps, boolean createsServiceRecord, boolean drawsFromPool, Verdict verdict, String reason,
        List<String> summary) {

    public enum Verdict { LAUNCHABLE, NOT_LAUNCHABLE_HERE, FALLBACK }

    public enum Decision { RUN, SKIPPED_OPTIONAL, SKIPPED_PRECONDITION, NO_ADAPTER }

    @JsonPropertyOrder({"id", "name", "family"})
    public record CfsRef(String id, String name, String family) {
    }

    /**
     * @param order    1-based position in the run order
     * @param seam     the seam
     * @param rfsName  the resource-facing service the CFS declares for it
     * @param required whether the edge marks it required
     * @param decision what the executor would do
     * @param reason   why, in words
     * @param consumed the consumed characteristics: value, or "missing"
     * @param vendor   who serves the seam for this tenant; null when no adapter
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"order", "seam", "rfsName", "required", "decision", "reason", "consumed", "vendor"})
    public record Step(int order, String seam, String rfsName, boolean required, Decision decision, String reason,
            Map<String, String> consumed, String vendor) {
    }
}
