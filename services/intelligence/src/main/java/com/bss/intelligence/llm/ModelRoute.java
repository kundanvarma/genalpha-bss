package com.bss.intelligence.llm;

/**
 * One resolved way to a model: the adapter that will be called, and what the
 * ledger says about it — provider, model and jurisdiction — decided BEFORE
 * anything is sent, so a residency rule can refuse it first.
 */
public record ModelRoute(LlmAdapter adapter, String provider, String model, String jurisdiction) {

    /** A route that could not be resolved (a misconfigured tenant stack). */
    public static ModelRoute unresolved() {
        return new ModelRoute(null, Jurisdiction.UNKNOWN, Jurisdiction.UNKNOWN, Jurisdiction.UNKNOWN);
    }

    /** The plain adapter's own route: its self-report and today's jurisdiction tag. */
    public static ModelRoute of(LlmAdapter adapter, LlmAdapter.Tier tier) {
        String provider = adapter.provider(tier);
        return new ModelRoute(adapter, provider, adapter.model(tier), Jurisdiction.defaultFor(provider));
    }
}
