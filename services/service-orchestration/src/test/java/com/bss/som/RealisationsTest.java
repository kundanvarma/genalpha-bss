package com.bss.som;

import com.bss.som.client.CatalogClient.Rfs;
import com.bss.som.service.Realisations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog-to-provisioning step 2: what the orchestrator did, matched against
 * what the CFS declared. Declared and exercised → a declared realisation;
 * exercised but not declared → an undeclared one; declared but never
 * exercised → unrealised. Both mismatches are what the gate counts.
 */
class RealisationsTest {

    private static final Rfs NUMBER = new Rfs("rfs-number", "Number", "number", List.of(), "rs-number", "Number pool");
    private static final Rfs OCS = new Rfs("rfs-ocs", "Online-charging subscriber", "ocs",
            List.of("chargingSpecId", "zeroRatedApps"), null, null);
    private static final Rfs NAMELESS = new Rfs("rfs-x", "Something", null, List.of(), null, null);

    @Test
    void aDeclaredSeamMatchesItsRfsCaseInsensitively() {
        assertThat(Realisations.declaredFor(List.of(NUMBER, OCS), "OCS")).contains(OCS);
        assertThat(Realisations.declaredFor(List.of(NUMBER, OCS), "number")).contains(NUMBER);
    }

    @Test
    void anExercisedSeamTheCfsNeverDeclaredIsUndeclared() {
        assertThat(Realisations.declaredFor(List.of(NUMBER), "sim")).isEmpty();
        assertThat(Realisations.declaredFor(List.of(), "number")).isEmpty();
        assertThat(Realisations.declaredFor(null, "number")).isEmpty();
        assertThat(Realisations.declaredFor(List.of(NUMBER), null)).isEmpty();
    }

    @Test
    void aDeclaredRfsTheCodeNeverExercisedIsUnrealised() {
        assertThat(Realisations.unrealised(List.of(NUMBER, OCS), Set.of("number", "sim")))
                .containsExactly(OCS);
        assertThat(Realisations.unrealised(List.of(NUMBER, OCS), Set.of("number", "ocs"))).isEmpty();
        // an RFS that names no seam can never be realised — it is always a finding
        assertThat(Realisations.unrealised(List.of(NAMELESS), Set.of("number"))).containsExactly(NAMELESS);
    }

    @Test
    void theSeamsAreExactlyWhatTheOrchestratorDrivesToday() {
        assertThat(Realisations.SEAMS).containsExactlyInAnyOrder(
                "number", "sim", "ocs", "slice", "wholesale-access", "partner-entitlement", "cpe");
    }
}
