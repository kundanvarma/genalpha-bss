package com.bss.som;

import com.bss.som.client.CatalogClient.Rfs;
import com.bss.som.seam.FulfilmentPlan;
import com.bss.som.seam.FulfilmentPlan.Verdict;
import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamRegistry;
import com.bss.som.seam.SeamResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executor's plan, without a fleet: the declared set is data, the order is
 * code, required always runs, optional runs when the product carries a consumed
 * value, an environment-gated seam decides for itself, and a seam nobody serves
 * is a fact the plan states rather than an error.
 */
class FulfilmentPlanTest {

    /** A stand-in adapter: fixed seam, optional precondition, no side effects. */
    private static SeamAdapter adapter(String seam, boolean gated, boolean applies) {
        return new SeamAdapter() {
            @Override public String seam() { return seam; }
            @Override public boolean environmentGated() { return gated; }
            @Override public boolean appliesTo(SeamContext ctx) { return applies; }
            @Override public Set<String> consumes() { return Set.of(); }
            @Override public Optional<String> vendor(String tenantId) { return Optional.of("test"); }
            @Override public SeamResult run(SeamContext ctx) { return SeamResult.realised("test", seam + "-ref"); }
        };
    }

    private static Rfs rfs(String seam, boolean required, String... consumes) {
        return new Rfs("rfs-" + seam, seam + " RFS", seam, List.of(consumes), "rs-" + seam, seam + " spec", required);
    }

    private static SeamContext ctx(Map<String, String> values) {
        return new SeamContext("t1", "cust", "svc", "so", "off", "Plan", "po", Map.of(), values, null, false);
    }

    private static final SeamRegistry REGISTRY = SeamRegistry.of(
            adapter("wholesale-access", true, false), adapter("partner-entitlement", false, true),
            adapter("number", false, true), adapter("edge-gpu", false, true), adapter("sim", false, true),
            adapter("ocs", false, true), adapter("slice", false, true), adapter("cpe", true, false));

    @Test
    void theSetIsDataAndTheOrderIsCode() {
        // declared in a silly order; planned in the orchestrator's fixed order
        List<Rfs> declared = List.of(rfs("ocs", false, "chargingSpecId"), rfs("sim", true), rfs("number", true, "msisdn"));
        FulfilmentPlan plan = FulfilmentPlan.of(declared, REGISTRY, ctx(Map.of("chargingSpecId", "plan-a")));
        assertThat(plan.running()).containsExactly("number", "sim", "ocs");
    }

    @Test
    void requiredRunsAndOptionalRunsOnlyWhenTheProductCarriesAConsumedValue() {
        List<Rfs> declared = List.of(rfs("number", true, "msisdn"), rfs("sim", true),
                rfs("ocs", false, "chargingSpecId", "zeroRatedApps"), rfs("slice", false, "sliceProfile"));
        FulfilmentPlan plain = FulfilmentPlan.of(declared, REGISTRY, ctx(Map.of()));
        assertThat(plain.running()).containsExactly("number", "sim");
        assertThat(plain.seams().stream().filter(p -> p.seam().equals("ocs")).findFirst().orElseThrow().verdict())
                .isEqualTo(Verdict.SKIP_OPTIONAL);

        FulfilmentPlan charged = FulfilmentPlan.of(declared, REGISTRY, ctx(Map.of("chargingSpecId", "plan-a")));
        assertThat(charged.running()).containsExactly("number", "sim", "ocs");

        FulfilmentPlan sliced = FulfilmentPlan.of(declared, REGISTRY,
                ctx(Map.of("chargingSpecId", "plan-a", "sliceProfile", "priority-gold")));
        assertThat(sliced.running()).containsExactly("number", "sim", "ocs", "slice");
    }

    @Test
    void anEnvironmentGatedSeamDecidesForItselfAndOverridesTheOptionalRule() {
        // wholesale access is optional and consumes nothing the product carries — still judged by its precondition
        List<Rfs> declared = List.of(rfs("wholesale-access", false, "accessLayer", "speed"), rfs("cpe", false));
        FulfilmentPlan notServed = FulfilmentPlan.of(declared, REGISTRY, ctx(Map.of()));
        assertThat(notServed.running()).isEmpty();
        assertThat(notServed.seams()).extracting(FulfilmentPlan.PlannedSeam::verdict)
                .containsOnly(Verdict.SKIP_PRECONDITION);

        SeamRegistry served = SeamRegistry.of(adapter("wholesale-access", true, true), adapter("cpe", true, false));
        FulfilmentPlan onOwnerFibre = FulfilmentPlan.of(declared, served, ctx(Map.of()));
        assertThat(onOwnerFibre.running()).containsExactly("wholesale-access");
    }

    @Test
    void aSeamNobodyServesIsAFactNotAnError() {
        List<Rfs> declared = List.of(rfs("number", true), rfs("market-hub", true, "meteringPointId"));
        FulfilmentPlan plan = FulfilmentPlan.of(declared, REGISTRY, ctx(Map.of("meteringPointId", "7070…")));
        assertThat(plan.running()).containsExactly("number");
        FulfilmentPlan.PlannedSeam hub = plan.seams().stream().filter(p -> p.seam().equals("market-hub")).findFirst().orElseThrow();
        assertThat(hub.verdict()).isEqualTo(Verdict.NO_ADAPTER);
        assertThat(hub.why()).contains("no adapter serves seam 'market-hub'");
    }

    @Test
    void aPoolDrawingSeamTakesTheNumberSlot() {
        List<Rfs> compute = List.of(rfs("edge-gpu", true), rfs("ocs", false, "chargingSpecId"));
        FulfilmentPlan plan = FulfilmentPlan.of(compute, REGISTRY, ctx(Map.of()));
        assertThat(plan.running()).containsExactly("edge-gpu");
    }

    @Test
    void consumedValuesComeFromTheEdgeWithTheOrderItemWinningOverTheSpec() {
        List<Rfs> declared = List.of(rfs("number", true, "msisdn"), rfs("ocs", false, "chargingSpecId", "zeroRatedApps"));
        Map<String, String> values = FulfilmentPlan.consumedValues(declared,
                Map.of("chargingSpecId", "plan-a", "zeroRatedApps", "whatsapp,tiktok", "unrelated", "x"),
                Map.of("msisdn", "+4790000001", "chargingSpecId", "plan-b"));
        assertThat(values).containsOnly(
                Map.entry("msisdn", "+4790000001"),
                Map.entry("chargingSpecId", "plan-b"),
                Map.entry("zeroRatedApps", "whatsapp,tiktok"));
    }

    @Test
    void aRequiredSeamWhosePreconditionFailsIsSkippedAndSaysWhy() {
        SeamRegistry registry = SeamRegistry.of(adapter("number", false, false));
        FulfilmentPlan plan = FulfilmentPlan.of(List.of(rfs("number", true)), registry, ctx(Map.of()));
        assertThat(plan.running()).isEmpty();
        assertThat(plan.seams().get(0).verdict()).isEqualTo(Verdict.SKIP_PRECONDITION);
    }
}
