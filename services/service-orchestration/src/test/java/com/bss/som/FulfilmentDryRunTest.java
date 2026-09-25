package com.bss.som;

import com.bss.som.client.CatalogClient;
import com.bss.som.client.CatalogClient.Cfs;
import com.bss.som.client.CatalogClient.Rfs;
import com.bss.som.dto.DryRunPlan;
import com.bss.som.dto.DryRunPlan.Decision;
import com.bss.som.dto.DryRunPlan.Verdict;
import com.bss.som.dto.DryRunRequest;
import com.bss.som.seam.SeamAdapter;
import com.bss.som.seam.SeamContext;
import com.bss.som.seam.SeamRegistry;
import com.bss.som.seam.SeamResult;
import com.bss.som.service.FulfilmentDryRun;
import com.bss.som.service.FulfilmentExecutor;
import com.bss.som.service.LineProvisioning;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dry run is the executor's plan with nothing run and nothing recorded:
 * a seam adapter that counts its calls stays at zero, the line provisioning
 * that writes realisations is never touched, and the verdict names the seam
 * a required RFS declares that no adapter serves here.
 */
class FulfilmentDryRunTest {

    /** A seam adapter that would blow up if a dry run ever ran it. */
    static SeamAdapter seam(String name, AtomicInteger runs, String vendor) {
        return new SeamAdapter() {
            @Override public String seam() { return name; }
            @Override public Set<String> consumes() { return Set.of(); }
            @Override public Optional<String> vendor(String tenantId) { return Optional.ofNullable(vendor); }
            @Override public SeamResult run(SeamContext ctx) { runs.incrementAndGet(); throw new AssertionError("a dry run ran a seam"); }
        };
    }

    static Rfs rfs(String seam, boolean required, String... consumes) {
        return new Rfs("rfs-" + seam, seam + " RFS", seam, List.of(consumes), "rs-" + seam, seam + " spec", required);
    }

    @Test
    void plansWithoutRunningAnything_andNamesTheSeamNobodyServes() {
        AtomicInteger runs = new AtomicInteger();
        SeamRegistry registry = SeamRegistry.of(seam("number", runs, "own-pool"), seam("ocs", runs, "mock"));
        CatalogClient catalog = mock(CatalogClient.class);
        LineProvisioning lines = mock(LineProvisioning.class);
        when(catalog.nameOf("off-1")).thenReturn(Optional.of("Energy line"));
        when(catalog.categoryOf("off-1")).thenReturn(Optional.of("Mobile plans"));
        when(catalog.cfsOf("off-1")).thenReturn(Optional.of(new Cfs("cfs-1", "Electricity supply", "energy")));
        when(catalog.rfsOfIfReadable("cfs-1")).thenReturn(Optional.of(List.of(
                rfs("number", true, "msisdn"), rfs("market-hub", true, "meteringPointId"), rfs("ocs", false, "chargingSpecId"))));
        when(catalog.specCharacteristicsOf("off-1")).thenReturn(Map.of());
        FulfilmentDryRun dryRun = new FulfilmentDryRun(catalog, new FulfilmentExecutor(registry, catalog, lines), registry);

        DryRunPlan plan = dryRun.plan("genalpha", new DryRunRequest("off-1", null, null));

        assertThat(runs.get()).isZero();
        verify(lines, never()).realise(anyString(), any(), any(), anyString(), any(), any());
        assertThat(plan.verdict()).isEqualTo(Verdict.NOT_LAUNCHABLE_HERE);
        assertThat(plan.reason()).contains("no adapter for market-hub");
        assertThat(plan.steps()).extracting(s -> s.seam() + ":" + s.decision())
                .containsExactly("number:RUN", "ocs:SKIPPED_OPTIONAL", "market-hub:NO_ADAPTER");
        assertThat(plan.steps().get(0).vendor()).isEqualTo("own-pool");
        assertThat(plan.steps().get(2).consumed()).containsEntry("meteringPointId", "missing");
        assertThat(plan.summary().get(0)).isEqualTo("1. Register a number (own-pool).");
        assertThat(plan.summary().get(plan.summary().size() - 1)).startsWith("Cannot launch here");
        assertThat(plan.drawsFromPool()).isTrue();
    }

    @Test
    void anOptionalSeamRunsWhenTheProductCarriesWhatItConsumes() {
        AtomicInteger runs = new AtomicInteger();
        SeamRegistry registry = SeamRegistry.of(seam("number", runs, null), seam("ocs", runs, "sigscale"));
        CatalogClient catalog = mock(CatalogClient.class);
        when(catalog.nameOf("off-2")).thenReturn(Optional.of("Mobile 30 GB"));
        when(catalog.categoryOf("off-2")).thenReturn(Optional.of("Mobile plans"));
        when(catalog.cfsOf("off-2")).thenReturn(Optional.of(new Cfs("cfs-2", "Mobile line", "mobile")));
        when(catalog.rfsOfIfReadable("cfs-2")).thenReturn(Optional.of(List.of(rfs("number", true), rfs("ocs", false, "chargingSpecId"))));
        when(catalog.specCharacteristicsOf("off-2")).thenReturn(Map.of("chargingSpecId", "plan-30gb"));
        FulfilmentDryRun dryRun = new FulfilmentDryRun(catalog, new FulfilmentExecutor(registry, catalog, mock(LineProvisioning.class)), registry);

        DryRunPlan plan = dryRun.plan("genalpha", new DryRunRequest("off-2", null, null));

        assertThat(plan.verdict()).isEqualTo(Verdict.LAUNCHABLE);
        assertThat(plan.steps()).extracting(DryRunPlan.Step::decision).containsExactly(Decision.RUN, Decision.RUN);
        assertThat(plan.steps().get(1).consumed()).containsEntry("chargingSpecId", "plan-30gb");
        assertThat(plan.summary()).contains("2. Set up charging (sigscale).");
        assertThat(runs.get()).isZero();
    }

    @Test
    void noCfsIsTheFallback_andBillingOnlyProvisionsNothing() {
        SeamRegistry registry = SeamRegistry.of();
        CatalogClient catalog = mock(CatalogClient.class);
        when(catalog.nameOf(anyString())).thenReturn(Optional.of("x"));
        when(catalog.categoryOf("off-3")).thenReturn(Optional.of("Insurance"));
        when(catalog.cfsOf("off-3")).thenReturn(Optional.empty());
        when(catalog.categoryOf("off-4")).thenReturn(Optional.of("Top-ups"));
        when(catalog.cfsOf("off-4")).thenReturn(Optional.of(new Cfs("cfs-b", "Billing-only product", "billing-only")));
        when(catalog.rfsOfIfReadable("cfs-b")).thenReturn(Optional.of(List.of()));
        when(catalog.specCharacteristicsOf(anyString())).thenReturn(Map.of());
        FulfilmentDryRun dryRun = new FulfilmentDryRun(catalog, new FulfilmentExecutor(registry, catalog, mock(LineProvisioning.class)), registry);

        DryRunPlan fallback = dryRun.plan("genalpha", new DryRunRequest("off-3", null, null));
        assertThat(fallback.verdict()).isEqualTo(Verdict.FALLBACK);
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.reason()).contains("Insurance").contains("name a fulfilment pattern");

        DryRunPlan billing = dryRun.plan("genalpha", new DryRunRequest("off-4", null, null));
        assertThat(billing.verdict()).isEqualTo(Verdict.LAUNCHABLE);
        assertThat(billing.createsServiceRecord()).isFalse();
        assertThat(billing.summary()).contains("Nothing to provision: this product only bills.");
    }
}
