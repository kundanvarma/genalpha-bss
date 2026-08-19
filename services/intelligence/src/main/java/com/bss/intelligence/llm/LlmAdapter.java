package com.bss.intelligence.llm;

/**
 * The vendor seam: everything above this line is prompt assembly, redaction
 * and audit; everything below is one HTTP dialect. Swapping the model is
 * configuration ("bss.intelligence.provider"), never a code change —
 * the same rule the BSS applies to IdPs, databases and brokers.
 */
public interface LlmAdapter {

    /** The kind of thinking a task needs — declared AT THE CALL SITE,
     * because the developer knows the stakes: FAST for volume work
     * (summaries, copy, narratives), SMART for judgment (proposals,
     * recommendations). Providers without tiers ignore it. */
    enum Tier { FAST, SMART }

    String complete(String system, String user);

    default String complete(Tier tier, String system, String user) {
        return complete(system, user);
    }

    /** Which provider/model answered — recorded in the audit ledger. */
    String provider();

    /** Tier-aware self-report: the ledger must name who ACTUALLY answered a
     * tiered call (a tenant can run local-FAST + frontier-SMART at once —
     * the tier-blind report once labelled local calls as the frontier). */
    default String provider(Tier tier) {
        return provider();
    }

    default String model(Tier tier) {
        return model();
    }

    String model();
}
