package com.bss.som.service;

import com.bss.som.client.CatalogClient;
import com.bss.som.client.CatalogClient.Cfs;
import com.bss.som.client.CatalogClient.Rfs;
import com.bss.som.dto.DryRunPlan;
import com.bss.som.dto.DryRunPlan.CfsRef;
import com.bss.som.dto.DryRunPlan.Decision;
import com.bss.som.dto.DryRunPlan.Step;
import com.bss.som.dto.DryRunPlan.Verdict;
import com.bss.som.dto.DryRunRequest;
import com.bss.som.seam.FulfilmentPlan;
import com.bss.som.seam.FulfilmentPlan.PlannedSeam;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The executor's plan for an offering with no adapter called (CONTEXT.md: dry
 * run). Same chain walk, same fixed order, same optional rule and preconditions
 * as a real order; the only difference is that nothing runs and nothing is
 * recorded — no service record, no realisation, no resource. A product manager
 * reads it before launch; launch governance turns it into a readiness item.
 */
@Service
public class FulfilmentDryRun {

    /** What each seam does, in the words the offering page uses. */
    static final Map<String, String> WORDS = Map.of(
            "wholesale-access", "Order the access line from the network owner",
            "partner-entitlement", "Activate with the partner",
            "number", "Register a number",
            "edge-gpu", "Reserve a GPU at the edge",
            "sim", "Provision the SIM",
            "ocs", "Set up charging",
            "slice", "Bind the network slice",
            "cpe", "Manage the customer's equipment");

    private final CatalogClient catalog;
    private final FulfilmentExecutor executor;
    private final SeamRegistry registry;

    public FulfilmentDryRun(CatalogClient catalog, FulfilmentExecutor executor, SeamRegistry registry) {
        this.catalog = catalog;
        this.executor = executor;
        this.registry = registry;
    }

    public DryRunPlan plan(String tenant, DryRunRequest request) {
        String offeringId = request.offeringId();
        String name = catalog.nameOf(offeringId).orElse(offeringId);
        String category = catalog.categoryOf(offeringId).orElse(null);
        Optional<Cfs> cfs = catalog.cfsOf(offeringId);
        if (cfs.isEmpty()) {
            return fallback(offeringId, name, category, null,
                    "the product specification names no customer-facing service — the category table decides");
        }
        Optional<List<Rfs>> readable = catalog.rfsOfIfReadable(cfs.get().id());
        CfsRef ref = new CfsRef(cfs.get().id(), cfs.get().name(), cfs.get().family());
        if (readable.isEmpty()) {
            return fallback(offeringId, name, category, ref, "the resource-facing services of '" + cfs.get().name()
                    + "' could not be read — an order would fall back to the category");
        }
        List<Rfs> declared = readable.get();
        Map<String, Object> item = item(request);
        Map<String, String> values = executor.consumedValues(declared, offeringId, item);
        String wish = request.characteristics() == null ? null : request.characteristics().get("msisdn");
        SeamContext ctx = new SeamContext(tenant, null, null, null, offeringId, name, null, item, values, wish,
                request.postCode() != null && !request.postCode().isBlank());
        FulfilmentPlan plan = executor.plan(declared, ctx);

        List<Step> steps = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        boolean blocked = false;
        int n = 0;
        for (PlannedSeam p : plan.seams()) {
            n++;
            Decision decision = switch (p.verdict()) {
                case RUN -> Decision.RUN;
                case SKIP_OPTIONAL -> Decision.SKIPPED_OPTIONAL;
                case SKIP_PRECONDITION -> Decision.SKIPPED_PRECONDITION;
                case NO_ADAPTER -> Decision.NO_ADAPTER;
            };
            String vendor = registry.forSeam(p.seam()).flatMap(a -> a.vendor(tenant)).orElse(null);
            Map<String, String> consumed = new LinkedHashMap<>();
            for (String c : p.rfs().consumes()) {
                String v = values.get(c);
                consumed.put(c, v == null || v.isBlank() ? "missing" : v);
            }
            steps.add(new Step(n, p.seam(), p.rfs().name(), p.rfs().required(), decision, p.why(), consumed, vendor));
            summary.add(sentence(n, p, decision, vendor));
            if (decision == Decision.NO_ADAPTER && p.rfs().required()) {
                blocked = true;
            }
        }
        boolean billingOnly = declared.isEmpty() && "billing-only".equals(cfs.get().family());
        boolean drawsFromPool = plan.running().contains("number") || plan.running().contains("edge-gpu");
        Verdict verdict = blocked ? Verdict.NOT_LAUNCHABLE_HERE : Verdict.LAUNCHABLE;
        String reason;
        if (blocked) {
            reason = "cannot launch here: " + steps.stream()
                    .filter(s -> s.decision() == Decision.NO_ADAPTER && s.required())
                    .map(s -> "no adapter for " + s.seam()).reduce((a, b) -> a + ", " + b).orElse("");
        } else if (billingOnly) {
            reason = "can launch here: nothing to provision, the product only bills";
        } else if (declared.isEmpty()) {
            reason = "can launch here: realised inside this BSS, nothing to provision";
        } else {
            reason = "can launch here";
        }
        if (declared.isEmpty()) {
            summary.add(billingOnly ? "Nothing to provision: this product only bills."
                    : "Nothing to provision outside this BSS: the service record is the service.");
        }
        summary.add(capitalise(reason) + ".");
        return new DryRunPlan(offeringId, name, ref, false, category, List.copyOf(steps), !billingOnly, drawsFromPool,
                verdict, reason, List.copyOf(summary));
    }

    private static DryRunPlan fallback(String offeringId, String name, String category, CfsRef ref, String why) {
        String reason = "falls back to the category" + (category == null ? "" : " '" + category + "'")
                + " — name a fulfilment pattern on the product specification";
        return new DryRunPlan(offeringId, name, ref, true, category, List.of(), true, false, Verdict.FALLBACK, reason,
                List.of(capitalise(why) + ".", capitalise(reason) + "."));
    }

    private static String sentence(int n, PlannedSeam p, Decision decision, String vendor) {
        String what = WORDS.getOrDefault(p.seam(), p.seam());
        String who = vendor == null ? "" : " (" + vendor + ")";
        return switch (decision) {
            case RUN -> n + ". " + what + who + ".";
            case SKIPPED_OPTIONAL, SKIPPED_PRECONDITION -> n + ". " + what + ": skipped — " + p.why() + ".";
            case NO_ADAPTER -> n + ". " + what + ": no adapter serves seam '" + p.seam() + "' in this fleet"
                    + (p.rfs().required() ? " — required, so the product cannot be fulfilled here"
                    : " — optional, so an order would carry on") + ".";
        };
    }

    /** A stand-in order item: a place when a post code was given, and the characteristics as the shopper would pick them. */
    private static Map<String, Object> item(DryRunRequest request) {
        Map<String, Object> product = new LinkedHashMap<>();
        if (request.postCode() != null && !request.postCode().isBlank()) {
            product.put("place", List.of(Map.of("@type", "GeographicAddress", "postCode", request.postCode().trim())));
        }
        if (request.characteristics() != null) {
            List<Map<String, Object>> chars = new ArrayList<>();
            request.characteristics().forEach((k, v) -> chars.add(Map.of("name", k, "value", v == null ? "" : v)));
            product.put("productCharacteristic", chars);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("product", product);
        return item;
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
