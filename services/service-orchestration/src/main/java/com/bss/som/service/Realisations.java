package com.bss.som.service;

import com.bss.som.client.CatalogClient.Rfs;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matching what the orchestrator DID (a seam it exercised) against what the
 * catalog SAID (the RFS list the product spec's CFS declares). Pure, so the
 * rule is unit-tested without a fleet; the gate in the proof run applies the
 * same rule to inventory rows.
 */
public final class Realisations {

    /** The seams the orchestrator can exercise today; a CFS declaring another one is a catalog mistake. */
    public static final Set<String> SEAMS = Set.of(
            "number", "sim", "ocs", "slice", "wholesale-access", "partner-entitlement", "cpe");

    private Realisations() {
    }

    /** The declared RFS for a seam — the first one whose seam matches; empty = the code did something undeclared. */
    public static Optional<Rfs> declaredFor(List<Rfs> declared, String seam) {
        if (declared == null || seam == null) {
            return Optional.empty();
        }
        return declared.stream().filter(r -> seam.equalsIgnoreCase(r.seam())).findFirst();
    }

    /** Declared RFS whose seam the orchestrator never exercised — the other half of the disagreement. */
    public static List<Rfs> unrealised(List<Rfs> declared, Set<String> exercisedSeams) {
        if (declared == null) {
            return List.of();
        }
        return declared.stream()
                .filter(r -> r.seam() == null || !exercisedSeams.contains(r.seam().toLowerCase(java.util.Locale.ROOT)))
                .collect(Collectors.toList());
    }
}
