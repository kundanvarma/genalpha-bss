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

    /** The answer with the provider's own token counts, when it reports them
     * (#422). An adapter that cannot read usage answers text only, and the
     * governor estimates. */
    default LlmCompletion completeWithUsage(Tier tier, String system, String user) {
        return LlmCompletion.textOnly(complete(tier, system, user));
    }

    /** Who will answer this tier, decided before anything is sent — so the
     * governor can refuse a route on residency before the prompt leaves. */
    default ModelRoute route(Tier tier) {
        return ModelRoute.of(this, tier);
    }

    /** The tenant's second choice for this tier, tried once when the first
     * fails on a connection error, a timeout, a 5xx or a 429. None by default. */
    default java.util.Optional<ModelRoute> fallbackRoute(Tier tier) {
        return java.util.Optional.empty();
    }

    /** The base URL this adapter actually sends to, defaults resolved; null when it
     * sends nothing (the stub) or cannot say. Jurisdiction reads it: a call to a
     * known public endpoint is that endpoint's jurisdiction, whatever was declared. */
    default String endpoint() {
        return null;
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
