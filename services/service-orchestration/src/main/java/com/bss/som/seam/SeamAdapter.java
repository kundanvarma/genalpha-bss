package com.bss.som.seam;

import java.util.Optional;
import java.util.Set;

/**
 * The one piece of code behind a seam (CONTEXT.md: seam adapter). It says
 * which seam it serves, when it applies, which characteristics it can
 * consume, and it runs the vendor's call. One adapter per seam is registered
 * by name in the {@link SeamRegistry}; the executor asks the registry, never a
 * fulfilment family, which code to run. Adding a seam — a content platform
 * for TV, a market hub for energy — is adding one adapter and a resource spec.
 */
public interface SeamAdapter {

    /** The seam name the catalog's resource specifications use (number, sim, ocs, slice, …). */
    String seam();

    /**
     * The precondition: whether this seam applies to this order at all. Most
     * seams always apply; an ENVIRONMENT-GATED seam (wholesale access: an owner
     * serves the address; CPE: never at order time) decides here, and the
     * executor lets that decision override the optional rule.
     */
    default boolean appliesTo(SeamContext ctx) {
        return true;
    }

    /** True when {@link #appliesTo} is the authority for an optional RFS on this seam, not the consumed values. */
    default boolean environmentGated() {
        return false;
    }

    /** The characteristic names this adapter can read from the consumed-values map. */
    Set<String> consumes();

    /** The vendor serving this seam for the tenant; empty when the adapter cannot say. */
    Optional<String> vendor(String tenantId);

    /** Run the seam. Never throws for a business "nothing to do": return {@link SeamResult#skipped}. */
    SeamResult run(SeamContext ctx);
}
