package com.bss.catalog;

import com.bss.catalog.seo.CrawlerGroup;
import com.bss.catalog.seo.CrawlerPolicy;
import com.bss.catalog.seo.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crawler posture, per bot and per state.
 *
 * The expensive assertion here is the first one. `search-only` is live on a
 * tenant, and the whole point of adding a fourth state was to add one — not to
 * quietly redefine a posture an operator already chose. So the exact 337 bytes
 * that tenant's robots.txt served on 3 Oct 2026, before any of this existed,
 * are pinned as a literal. A reorder, an added vendor or a reworded directive
 * turns this red, which is the only way "unchanged" is a fact and not a hope.
 */
class CrawlerPolicyTest {

    /**
     * Captured from the running fleet at shop.nova.localhost:8080/robots.txt
     * on 3 Oct 2026, before the split: sha256
     * 7f87a046bdb3142e5aa3b4e4b166d5680a09f72ff850a914f1b7f3ed12c9ee8d, 337 bytes.
     */
    private static final String SEARCH_ONLY_BEFORE = """
            User-agent: GPTBot
            Disallow: /

            User-agent: OAI-SearchBot
            Disallow: /

            User-agent: ClaudeBot
            Disallow: /

            User-agent: anthropic-ai
            Disallow: /

            User-agent: PerplexityBot
            Disallow: /

            User-agent: Google-Extended
            Disallow: /

            User-agent: CCBot
            Disallow: /

            User-agent: Bytespider
            Disallow: /

            User-agent: *
            Allow: /

            Sitemap: /sitemap.xml
            """;

    /** Captured the same way from the open tenant (localhost:8080) and the dark one (fjord). */
    private static final String OPEN_BEFORE = "User-agent: *\nAllow: /\n\nSitemap: /sitemap.xml\n";
    private static final String DARK_BEFORE = "User-agent: *\nDisallow: /\n";

    private final CrawlerPolicy policy = new CrawlerPolicy();

    @Test
    void searchOnlyIsByteForByteWhatItServedBeforeTheFourthStateExisted() {
        String now = policy.robotsTxt(Visibility.SEARCH_ONLY);
        assertEquals(SEARCH_ONLY_BEFORE, now,
                "search-only is live on a tenant; its document may not change");
        assertEquals(337, now.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void openAndDarkAreUnchangedToo() {
        assertEquals(OPEN_BEFORE, policy.robotsTxt(Visibility.OPEN));
        assertEquals(DARK_BEFORE, policy.robotsTxt(Visibility.DARK));
    }

    /**
     * THE DEFECT, stated as a test: every bot in the default roster, against
     * every state. A single string match would have passed on the old code.
     */
    @Test
    void everyBotIsAnsweredByEveryState() {
        // what each vendor documents the bot as doing (the roster's own tags)
        Map<String, CrawlerGroup> expected = Map.of(
                "GPTBot", CrawlerGroup.TRAINING,
                "OAI-SearchBot", CrawlerGroup.RETRIEVAL,
                "ClaudeBot", CrawlerGroup.TRAINING,
                "anthropic-ai", CrawlerGroup.TRAINING,
                "PerplexityBot", CrawlerGroup.RETRIEVAL,
                "Google-Extended", CrawlerGroup.TRAINING,
                "CCBot", CrawlerGroup.TRAINING,
                "Bytespider", CrawlerGroup.TRAINING);
        assertEquals(expected.size(), policy.roster().size());

        for (CrawlerPolicy.Crawler c : policy.roster()) {
            assertEquals(expected.get(c.userAgent()), c.group(),
                    c.userAgent() + " is in the wrong group");

            // dark: the document names nobody, because it allows nobody
            assertFalse(named(Visibility.DARK, c.userAgent()),
                    "dark blocks everyone with one wildcard, not per bot");

            // search-only: blocked, retrieval and training alike (unchanged)
            assertTrue(named(Visibility.SEARCH_ONLY, c.userAgent()),
                    "search-only must still disallow " + c.userAgent());

            // search-ai: training blocked by name, retrieval left to the wildcard Allow
            assertEquals(c.group() == CrawlerGroup.TRAINING,
                    named(Visibility.SEARCH_AI, c.userAgent()),
                    "search-ai got " + c.userAgent() + " wrong");

            // open: nobody is named
            assertFalse(named(Visibility.OPEN, c.userAgent()),
                    "open must not disallow " + c.userAgent());
        }
    }

    @Test
    void searchAiSaysAiSearchYesAndTrainingNoInOneDocument() {
        String doc = policy.robotsTxt(Visibility.SEARCH_AI);

        // the posture the three-state switch could not express, in one file
        assertTrue(doc.contains("User-agent: GPTBot\nDisallow: /"),
                "GPTBot trains — it must be disallowed");
        assertFalse(doc.contains("OAI-SearchBot"),
                "OAI-SearchBot answers questions — it must NOT be disallowed");
        assertFalse(doc.contains("PerplexityBot"),
                "PerplexityBot answers questions — it must NOT be disallowed");
        // and classic search still works
        assertTrue(doc.contains("User-agent: *\nAllow: /"));
        assertTrue(doc.endsWith("Sitemap: /sitemap.xml\n"));
    }

    @Test
    void theRosterIsConfigurationSoAVendorRenameNeedsNoRelease() {
        CrawlerPolicy renamed = new CrawlerPolicy();
        renamed.setCrawlers(List.of("GPTBot-2:training", " NewSearchBot : retrieval "));

        assertEquals(List.of("GPTBot-2", "NewSearchBot"),
                renamed.roster().stream().map(CrawlerPolicy.Crawler::userAgent).toList());
        assertTrue(renamed.robotsTxt(Visibility.SEARCH_AI).contains("User-agent: GPTBot-2"));
        assertFalse(renamed.robotsTxt(Visibility.SEARCH_AI).contains("NewSearchBot"));
    }

    @Test
    void aMalformedEntryNeverBecomesAWildcardOrAHalfDirective() {
        CrawlerPolicy sloppy = new CrawlerPolicy();
        sloppy.setCrawlers(List.of("", "   ", ":retrieval", "Mystery", "Known:training"));

        // a blank user-agent would read as "User-agent: " and match nothing
        // useful; an unknown group is read as training, the cautious answer
        assertEquals(List.of("Mystery", "Known"),
                sloppy.roster().stream().map(CrawlerPolicy.Crawler::userAgent).toList());
        assertEquals(CrawlerGroup.TRAINING, sloppy.roster().get(0).group());
        assertFalse(sloppy.robotsTxt(Visibility.SEARCH_AI).contains("User-agent: \n"));
    }

    @Test
    void anUnknownOrAbsentStateFallsBackToTheCautiousOne() {
        assertEquals(Visibility.SEARCH_ONLY, Visibility.of(null));
        assertEquals(Visibility.SEARCH_ONLY, Visibility.of("serch-ai"));
        assertEquals(Visibility.SEARCH_AI, Visibility.of(" Search-AI "));
        assertEquals(Visibility.OPEN, Visibility.of("open"));
        assertEquals(Visibility.DARK, Visibility.of("dark"));
    }

    @Test
    void llmsTxtAndNoindexFollowTheState() {
        assertTrue(Visibility.OPEN.publishesLlmsTxt());
        assertTrue(Visibility.SEARCH_AI.publishesLlmsTxt(),
                "an AI answer engine is welcome here, so the file it reads is published");
        assertFalse(Visibility.SEARCH_ONLY.publishesLlmsTxt());
        assertFalse(Visibility.DARK.publishesLlmsTxt());

        // Disallow stops a fetch, not a listing — only dark needs noindex
        assertTrue(Visibility.DARK.noindex());
        assertFalse(Visibility.SEARCH_ONLY.noindex());
        assertFalse(Visibility.SEARCH_AI.noindex());
        assertFalse(Visibility.OPEN.noindex());

        assertFalse(Visibility.DARK.crawlable());
        assertTrue(Visibility.SEARCH_ONLY.crawlable());
        assertTrue(Visibility.SEARCH_AI.crawlable());
        assertTrue(Visibility.OPEN.crawlable());
    }

    /** Is this user-agent named in the document this state publishes? */
    private boolean named(Visibility v, String userAgent) {
        return policy.robotsTxt(v).contains("User-agent: " + userAgent + "\n");
    }
}
