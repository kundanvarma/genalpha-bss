package com.bss.catalog.seo;

/**
 * What a crawler is FOR — the distinction `ai-visibility: search-only` could
 * not make, and the reason a tenant could not say "AI search yes, training no".
 *
 * The mapping from a user-agent to a group is each vendor's own documented
 * fact, not ours, so it lives in configuration (`bss.geo.crawlers`) and a
 * vendor rename or a new bot needs an environment variable, not a release.
 */
public enum CrawlerGroup {

    /** Fetches a page to answer a question now and cite it: OAI-SearchBot, PerplexityBot. */
    RETRIEVAL,
    /** Collects a page into a training corpus: GPTBot, ClaudeBot, CCBot, Google-Extended. */
    TRAINING;

    /** The spelling used in configuration; an unknown value is TRAINING — the cautious read. */
    public static CrawlerGroup of(String raw) {
        return raw != null && "retrieval".equalsIgnoreCase(raw.trim()) ? RETRIEVAL : TRAINING;
    }
}
