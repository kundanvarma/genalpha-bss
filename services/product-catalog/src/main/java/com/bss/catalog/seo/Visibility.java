package com.bss.catalog.seo;

/**
 * What a tenant has told crawlers it wants — the `ai-visibility` switch in
 * tenants.yml, as a type rather than a string compared in four places.
 *
 * There are four postures, and the middle two are the point. Before 3 Oct 2026
 * there were three, and `search-only` blocked OpenAI's GPTBot (which trains)
 * and OAI-SearchBot (which answers) in the same list. OpenAI documents those
 * as separately controllable, so the one posture an operator most often wants —
 * <em>show me in AI search, do not train on me</em> — could not be expressed at
 * all. {@link #SEARCH_AI} is that posture.
 *
 * {@link #SEARCH_ONLY} means exactly what it meant before: every AI crawler we
 * name is blocked, retrieval and training alike. A tenant is live on it, and
 * redefining a posture an operator already chose would change their privacy
 * stance without them asking. The suite pins its bytes.
 */
public enum Visibility {

    /** Nobody: no crawler, no sitemap, no page — and noindex, because a bare URL indexes without one. */
    DARK("dark"),
    /** Classic search yes; every named AI crawler no, retrieval and training alike. */
    SEARCH_ONLY("search-only"),
    /** Classic search yes, AI answer engines yes, model training no. */
    SEARCH_AI("search-ai"),
    /** Everyone welcome, training included. */
    OPEN("open");

    private final String key;

    Visibility(String key) {
        this.key = key;
    }

    /** The spelling used in tenants.yml. */
    public String key() {
        return key;
    }

    /**
     * Reads the tenant's switch. An absent or unrecognised value is
     * {@link #SEARCH_ONLY} — the same fallback the controller has always had,
     * so a typo in a tenant's configuration cannot silently open a tenant up.
     */
    public static Visibility of(String raw) {
        if (raw == null) {
            return SEARCH_ONLY;
        }
        String v = raw.trim().toLowerCase();
        for (Visibility candidate : values()) {
            if (candidate.key.equals(v)) {
                return candidate;
            }
        }
        return SEARCH_ONLY;
    }

    /** Whether crawler-facing pages and the sitemap exist at all. */
    public boolean crawlable() {
        return this != DARK;
    }

    /** llms.txt is published where AI answer engines are welcome. */
    public boolean publishesLlmsTxt() {
        return this == OPEN || this == SEARCH_AI;
    }

    /** Whether public pages must carry a noindex directive as well as a Disallow. */
    public boolean noindex() {
        return this == DARK;
    }

    /** Whether robots.txt disallows a crawler of this kind. */
    public boolean blocks(CrawlerGroup group) {
        return switch (this) {
            case DARK, SEARCH_ONLY -> true;
            case SEARCH_AI -> group == CrawlerGroup.TRAINING;
            case OPEN -> false;
        };
    }
}
