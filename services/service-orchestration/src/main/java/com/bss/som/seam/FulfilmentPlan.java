package com.bss.som.seam;

import com.bss.som.client.CatalogClient.Rfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The plan the executor runs for one order item: the seams the CFS declares,
 * in the orchestrator's fixed order, each with a verdict. Pure — no Spring,
 * no I/O — so the precedence is unit-tested without a fleet.
 *
 * <p>The rules, in one place:
 * <ol>
 * <li><b>The set is data, the order is code.</b> Seams run in {@link #ORDER};
 *     a CFS chooses which, never in what order. A number pool and a GPU pool
 *     share the same slot: a CFS declares one of them.</li>
 * <li><b>Required runs; optional runs when the product calls for it.</b> An
 *     RFS marked {@code required} runs for every order; an optional one runs
 *     when any of the characteristics it consumes carries a value on the
 *     product spec or the order item (charging only with a charging plan,
 *     slice only with a slice profile).</li>
 * <li><b>An environment-gated seam decides for itself.</b> Wholesale access
 *     (does an owner serve this address?) and CPE (never at order time) keep
 *     their adapter precondition, and it overrides the optional rule.</li>
 * <li><b>No adapter is a fact, not an error.</b> A declared seam nobody serves
 *     in this fleet is planned as {@link Verdict#NO_ADAPTER} and recorded as a
 *     realisation with vendor {@code no-adapter}, so the gate and the dry run
 *     can say "this product cannot be fulfilled here".</li>
 * </ol>
 */
public record FulfilmentPlan(List<PlannedSeam> seams) {

    /** The fixed order seams run in. Pool-drawing seams (number, edge-gpu) share the third slot. */
    public static final List<String> ORDER = List.of(
            "wholesale-access", "partner-entitlement", "number", "edge-gpu", "sim", "ocs", "slice", "cpe");

    public enum Verdict { RUN, SKIP_OPTIONAL, SKIP_PRECONDITION, NO_ADAPTER }

    /**
     * @param seam    the seam name
     * @param rfs     the RFS the CFS declares for it
     * @param verdict what the executor will do
     * @param why     the reason, for the log and the dry run
     */
    public record PlannedSeam(String seam, Rfs rfs, Verdict verdict, String why) {
        public boolean runs() {
            return verdict == Verdict.RUN;
        }
    }

    /** Build the plan for a CFS's declared RFS list against the registry, the consumed values and the order. */
    public static FulfilmentPlan of(List<Rfs> declared, SeamRegistry registry, SeamContext ctx) {
        List<PlannedSeam> out = new ArrayList<>();
        Map<String, Rfs> bySeam = new LinkedHashMap<>();
        for (Rfs r : declared == null ? List.<Rfs>of() : declared) {
            if (r.seam() != null) {
                bySeam.putIfAbsent(r.seam().toLowerCase(Locale.ROOT), r);
            }
        }
        // seams the code knows, in the fixed order
        for (String seam : ORDER) {
            Rfs rfs = bySeam.remove(seam);
            if (rfs != null) {
                out.add(judge(seam, rfs, registry, ctx));
            }
        }
        // seams the catalog declares that the orchestrator has never heard of
        for (Map.Entry<String, Rfs> e : bySeam.entrySet()) {
            out.add(new PlannedSeam(e.getKey(), e.getValue(), Verdict.NO_ADAPTER,
                    "no adapter serves seam '" + e.getKey() + "' in this fleet"));
        }
        return new FulfilmentPlan(List.copyOf(out));
    }

    private static PlannedSeam judge(String seam, Rfs rfs, SeamRegistry registry, SeamContext ctx) {
        Optional<SeamAdapter> adapter = registry.forSeam(seam);
        if (adapter.isEmpty()) {
            return new PlannedSeam(seam, rfs, Verdict.NO_ADAPTER, "no adapter serves seam '" + seam + "' in this fleet");
        }
        SeamAdapter a = adapter.get();
        if (a.environmentGated()) {
            return a.appliesTo(ctx)
                    ? new PlannedSeam(seam, rfs, Verdict.RUN, "environment calls for it")
                    : new PlannedSeam(seam, rfs, Verdict.SKIP_PRECONDITION, "the environment does not call for it here");
        }
        if (!rfs.required()) {
            List<String> present = new ArrayList<>();
            for (String name : rfs.consumes()) {
                String v = ctx.value(name);
                if (v != null && !v.isBlank()) {
                    present.add(name);
                }
            }
            if (present.isEmpty()) {
                return new PlannedSeam(seam, rfs, Verdict.SKIP_OPTIONAL,
                        "optional, and the product carries none of " + rfs.consumes());
            }
            return a.appliesTo(ctx)
                    ? new PlannedSeam(seam, rfs, Verdict.RUN, "the product carries " + present)
                    : new PlannedSeam(seam, rfs, Verdict.SKIP_PRECONDITION, "precondition not met");
        }
        return a.appliesTo(ctx)
                ? new PlannedSeam(seam, rfs, Verdict.RUN, "required")
                : new PlannedSeam(seam, rfs, Verdict.SKIP_PRECONDITION, "required, but the precondition is not met");
    }

    /**
     * The consumed-values map the edge names: for each consumed characteristic,
     * the order item's product characteristic wins over the product spec's.
     */
    public static Map<String, String> consumedValues(List<Rfs> declared, Map<String, String> specValues,
            Map<String, String> itemValues) {
        Map<String, String> out = new HashMap<>();
        for (Rfs r : declared == null ? List.<Rfs>of() : declared) {
            for (String name : r.consumes()) {
                String v = itemValues == null ? null : itemValues.get(name);
                if (v == null && specValues != null) {
                    v = specValues.get(name);
                }
                if (v != null) {
                    out.put(name, v);
                }
            }
        }
        return Map.copyOf(out);
    }

    /** The seams that will run, in order. */
    public List<String> running() {
        return seams.stream().filter(PlannedSeam::runs).map(PlannedSeam::seam).toList();
    }
}
