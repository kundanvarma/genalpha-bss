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

    /** The plain adapter's own route, nothing declared. */
    public static ModelRoute of(LlmAdapter adapter, LlmAdapter.Tier tier) {
        return of(adapter, tier, null);
    }

    /**
     * The plain adapter's own route: its self-report, and its jurisdiction — the
     * endpoint it calls when that is a known public one, else the operator's
     * declared word ({@code bss.intelligence.jurisdiction} for the shared
     * default), else the provider kind's default.
     */
    public static ModelRoute of(LlmAdapter adapter, LlmAdapter.Tier tier, String declared) {
        String provider = adapter.provider(tier);
        String jurisdiction;
        if ("stub".equals(provider)) {
            jurisdiction = Jurisdiction.NONE;
        } else {
            String known = Jurisdiction.ofEndpoint(adapter.endpoint());
            jurisdiction = known != null ? known : Jurisdiction.of(declared, provider);
        }
        return new ModelRoute(adapter, provider, adapter.model(tier), jurisdiction);
    }
}
