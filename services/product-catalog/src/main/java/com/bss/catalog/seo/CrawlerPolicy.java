package com.bss.catalog.seo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * THE CRAWLER ROSTER AND THE DOCUMENT IT GENERATES.
 *
 * robots.txt is the lever crawlers actually obey, so the tenant's posture has
 * to be expressible in it per bot. That needs two facts kept apart:
 *
 *  - WHICH bots exist and what each one is for — a third-party fact that drifts
 *    (OpenAI split GPTBot from OAI-SearchBot; vendors rename). It is read from
 *    `bss.geo.crawlers` as `user-agent:group` entries, so an operator corrects
 *    it with `BSS_GEO_CRAWLERS=...` and never waits for a release.
 *  - WHAT the tenant wants — {@link Visibility}, from tenants.yml.
 *
 * The roster is ONE ORDERED list rather than two group lists, on purpose. A
 * tenant is live on `search-only`, whose document names every bot; emitting a
 * retrieval block and then a training block would reorder that tenant's
 * robots.txt. Grouping is a tag on each entry, so the order the file ships in
 * is the order every posture emits, and `search-only` is byte-for-byte what it
 * was before the fourth state existed.
 *
 * The default roster is exactly the eight names `search-only` blocked on
 * 3 Oct 2026, each tagged with the group its vendor documents — no additions,
 * because adding a name would change a live tenant's document. Growing the
 * roster (Anthropic's Claude-SearchBot, Google's own retrieval agents) is a
 * configuration change an operator makes deliberately.
 */
@Component
@ConfigurationProperties(prefix = "bss.geo")
public class CrawlerPolicy {

    /** The tail every non-dark document ends with: classic search is always welcome. */
    private static final String CLASSIC_SEARCH = "User-agent: *\nAllow: /\n\nSitemap: /sitemap.xml\n";

    /** What a dark tenant publishes. Paired with a noindex header — Disallow alone leaves a bare URL indexable. */
    private static final String DARK_BODY = "User-agent: *\nDisallow: /\n";

    /** The noindex directive a dark tenant's public responses carry. */
    public static final String NOINDEX = "noindex, nofollow";

    private List<String> crawlers = new ArrayList<>(List.of(
            "GPTBot:training",
            "OAI-SearchBot:retrieval",
            "ClaudeBot:training",
            "anthropic-ai:training",
            "PerplexityBot:retrieval",
            "Google-Extended:training",
            "CCBot:training",
            "Bytespider:training"));

    /** One crawler and what it is for. */
    public record Crawler(String userAgent, CrawlerGroup group) { }

    public List<String> getCrawlers() {
        return crawlers;
    }

    public void setCrawlers(List<String> crawlers) {
        this.crawlers = crawlers;
    }

    /**
     * The configured roster, parsed and in file order. A malformed entry is
     * dropped rather than emitted: half a line in robots.txt is a directive
     * aimed at nobody, and a blank user-agent would match everything.
     */
    public List<Crawler> roster() {
        List<Crawler> out = new ArrayList<>();
        for (String entry : crawlers) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":", 2);
            String userAgent = parts[0].trim();
            if (userAgent.isEmpty()) {
                continue;
            }
            out.add(new Crawler(userAgent, CrawlerGroup.of(parts.length > 1 ? parts[1] : null)));
        }
        return List.copyOf(out);
    }

    /** The crawlers this posture disallows by name, in roster order. */
    public List<Crawler> disallowed(Visibility visibility) {
        return roster().stream().filter(c -> visibility.blocks(c.group())).toList();
    }

    /** The whole robots.txt body for a posture. */
    public String robotsTxt(Visibility visibility) {
        if (!visibility.crawlable()) {
            return DARK_BODY;
        }
        StringBuilder sb = new StringBuilder();
        for (Crawler c : disallowed(visibility)) {
            sb.append("User-agent: ").append(c.userAgent()).append("\nDisallow: /\n\n");
        }
        return sb.append(CLASSIC_SEARCH).toString();
    }
}
