package com.bss.catalog.controller;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.SchemaOrg;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.JsonLd;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.seo.CrawlerPolicy;
import com.bss.catalog.seo.PublicCatalog;
import com.bss.catalog.seo.PublicOffering;
import com.bss.catalog.seo.Visibility;
import com.bss.catalog.service.Channels;
import com.bss.catalog.service.LifecyclePolicy;
import com.bss.catalog.service.ProductOfferingService;
import com.bss.catalog.service.SchemaOrgProjection;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GENERATIVE DISCOVERABILITY (GEO): the crawler-facing face of the shop.
 * AI answer engines (GPTBot, ClaudeBot, PerplexityBot…) do not execute
 * JavaScript, so the SPA is invisible to them — these endpoints render the
 * SAME catalog facts as complete HTML with schema.org JSON-LD, generated
 * live from TMF620 (never authored, never synced; the suite proves the
 * bot price equals the catalog price). The gateway dual-serves by
 * User-Agent: humans get the SPA, crawlers get this.
 *
 * The structured data is SERIALISED from records ({@link SchemaOrg} through
 * {@link JsonLd}), not assembled from text, and the three facts that used to
 * be constants — availability, language, currency — are read from the
 * warehouse, the lifecycle and the tenant's own configuration
 * ({@link SchemaOrgProjection}).
 *
 * The per-tenant `ai-visibility` switch (open | search-ai | search-only |
 * dark) drives robots.txt — the lever crawlers actually obey. The roster of
 * crawlers and what each one is FOR lives in {@link CrawlerPolicy}, so a
 * tenant can say "AI search yes, training no" and a vendor rename needs no
 * release. llms.txt ships where AI answer engines are welcome, honestly
 * labeled: it is speculative courtesy, not the feature.
 */
@RestController
@RequestMapping("/seo")
public class GeoController {

    private final ProductOfferingService offerings;
    private final PublicCatalog catalog;
    private final SchemaOrgProjection projection;
    private final JsonLd jsonLd;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final CrawlerPolicy crawlers;
    private final LifecyclePolicy lifecycle;

    public GeoController(ProductOfferingService offerings, PublicCatalog catalog,
            SchemaOrgProjection projection, JsonLd jsonLd, TenantRegistry tenants,
            TenantScope tenantScope, CrawlerPolicy crawlers, LifecyclePolicy lifecycle) {
        this.offerings = offerings;
        this.catalog = catalog;
        this.projection = projection;
        this.jsonLd = jsonLd;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.crawlers = crawlers;
        this.lifecycle = lifecycle;
    }

    private TenantRegistry.TenantEntry tenant() {
        return tenants.byId(tenantScope.currentTenantId());
    }

    private Visibility visibility() {
        TenantRegistry.TenantEntry t = tenant();
        return Visibility.of(t == null ? null : t.getAiVisibility());
    }

    private String brand() {
        TenantRegistry.TenantEntry t = tenant();
        return t == null || t.getBrandName() == null ? "the operator" : t.getBrandName();
    }

    /* ---------- the bot-readable offering page ---------- */

    @GetMapping(value = "/offering/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> offering(@PathVariable("id") String id, HttpServletRequest request) {
        if (!visibility().crawlable()) {
            throw new NotFoundException("this operator is not visible to crawlers");
        }
        ProductOfferingDto offering = offerings.findById(id);
        TenantRegistry.TenantEntry tenant = tenant();
        SchemaOrgProjection.OfferingPage page = projection.page(offering, brand(),
                currency(tenant), baseUrl(request));
        SchemaOrg.Product product = page.product();

        // the structured data is SERIALISED from the record — Jackson owns the
        // escaping, so a name carrying a quote, a backslash or a newline is a
        // value, never a broken document
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"").append(esc(language(tenant)))
                .append("\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(esc(product.name())).append(" — ").append(esc(brand()))
                .append("</title>");
        if (product.description() != null) {
            html.append("<meta name=\"description\" content=\"").append(esc(product.description()))
                    .append("\">");
        }
        html.append("<link rel=\"canonical\" href=\"").append(esc(product.url())).append("\">")
                .append("<script type=\"application/ld+json\">").append(jsonLd.write(product))
                .append("</script></head><body>")
                .append("<h1>").append(esc(product.name())).append("</h1>");
        for (String image : product.image()) {
            html.append("<img src=\"").append(esc(image)).append("\" alt=\"")
                    .append(esc(product.name())).append("\">");
        }
        if (product.category() != null) {
            html.append("<p>Category: ").append(esc(product.category())).append("</p>");
        }
        if (product.description() != null) {
            html.append("<p>").append(esc(product.description())).append("</p>");
        }
        if (page.headline() != null) {
            html.append("<p>Price: <b>").append(esc(page.headline().text())).append("</b>");
            if (page.upfront() != null) {
                html.append(" plus ").append(esc(page.upfront().text())).append(" once");
            }
            html.append("</p>");
        }
        if (!product.additionalProperty().isEmpty()) {
            html.append("<dl>");
            for (SchemaOrg.PropertyValue property : product.additionalProperty()) {
                html.append("<dt>").append(esc(property.name())).append("</dt><dd>")
                        .append(esc(property.value()))
                        .append(property.unitText() == null ? "" : " " + esc(property.unitText()))
                        .append("</dd>");
            }
            html.append("</dl>");
        }
        html.append("<p>Sold by ").append(esc(brand())).append(". <a href=\"")
                .append(esc(product.url())).append("\">View in the shop</a></p>")
                .append("</body></html>");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html.toString());
    }

    /**
     * THE MACHINE-READABLE HEAD OF AN OFFERING PAGE, as data (#180).
     *
     * The server-rendered storefront owns the document's body; this owns the
     * facts in its head. That split settles a question the renderer could only
     * flag: the crawler page and the React app each had their own idea of which
     * price leads, and when the User-Agent branch above is retired one of them
     * has to be the authority. It is this one — the same
     * {@link SchemaOrgProjection} the bot page renders from, so retiring that
     * page cannot quietly change what a crawler is told.
     *
     * Returned as JSON rather than HTML because the renderer is assembling a
     * document, not forwarding one. The JSON-LD is embedded as a STRING: it is
     * already serialised by Jackson from the record, which is what makes a name
     * carrying a quote a value rather than a broken document, and re-parsing it
     * here only to re-serialise it there would be two more chances to break it.
     */
    @GetMapping(value = "/offering/{id}/meta", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> offeringMeta(@PathVariable("id") String id,
                                                            HttpServletRequest request) {
        if (!visibility().crawlable()) {
            throw new NotFoundException("this operator is not visible to crawlers");
        }
        /*
         * A PRODUCT THAT IS NO LONGER SOLD IS NOT A 404 AND NOT A PAGE.
         *
         * It is a URL with inbound links and an index entry, so answering 200
         * with a page nobody can buy from invites a crawler to keep it and a
         * person to arrive at a dead end — and answering 404 throws away every
         * link pointing at it. A permanent redirect is the honest answer: the
         * thing moved.
         *
         * WHERE TO, in the operator's own words first: TMF620 lets an offering
         * name a replacement, so if one is named AND is itself still sellable,
         * that is the canonical destination. Otherwise the shop root, which is
         * always true.
         *
         * NOT the category shelf, tempting as it is: a shelf is chosen by
         * mapping a category NAME to a slug, and that mapping lives in the
         * front end (pages/lines.jsx). Reproducing it here would make a fourth
         * copy of a list that already has three, which is how the shelf list
         * became a drift risk in the first place.
         */
        ProductOfferingService.Withdrawn withdrawn = offerings.withdrawn(id);
        if (withdrawn.retired()) {
            Map<String, String> gone = new java.util.LinkedHashMap<>();
            gone.put("redirect", replacementUrl(withdrawn.replacementId(), request));
            return ResponseEntity.ok(gone);
        }

        // not retired: the ordinary wall decides, and a 404 here is a 404 there
        ProductOfferingDto offering = offerings.findById(id);
        TenantRegistry.TenantEntry tenant = tenant();
        SchemaOrgProjection.OfferingPage page = projection.page(offering, brand(),
                currency(tenant), baseUrl(request));
        SchemaOrg.Product product = page.product();
        Map<String, String> meta = new java.util.LinkedHashMap<>();
        meta.put("title", product.name() + " — " + brand());
        meta.put("description", product.description() == null ? "" : product.description());
        meta.put("canonical", product.url() == null ? "" : product.url());
        meta.put("language", language(tenant));
        meta.put("jsonLd", jsonLd.write(product));
        return ResponseEntity.ok(meta);
    }


    /**
     * Where a withdrawn offering's URL should point: the successor it named, if
     * that successor is itself something the shop may show — decided by the
     * ordinary wall, not by this surface — else the shop root, which is always
     * true.
     *
     * NOT the category shelf, tempting as it is: a shelf is chosen by mapping a
     * category NAME to a slug, and that mapping lives in the front end
     * (pages/lines.jsx). Reproducing it here would make a fourth copy of a list
     * that already has three, which is how the shelf list became a drift risk.
     */
    private String replacementUrl(String replacementId, HttpServletRequest request) {
        String base = baseUrl(request);
        if (replacementId != null) {
            try {
                ProductOfferingDto candidate = offerings.findById(replacementId);
                return base + "/shop/offering/" + candidate.getId();
            } catch (RuntimeException e) {
                // the successor is gone, unlaunched or not for this channel —
                // not a reason to fail the redirect
            }
        }
        return base + "/shop/";
    }

    /* ---------- sitemap / robots / llms.txt ---------- */

    /**
     * ONE SITEMAP UNTIL IT IS TOO BIG, THEN AN INDEX OF SHARDS.
     *
     * The protocol caps a sitemap at 50,000 URLs (and 50 MB), and a crawler's
     * answer to a file over the cap is to ignore the overflow — silently, which
     * is the failure mode this whole arc keeps finding. A shop with more
     * offerings than that is not hypothetical for a wholesaler.
     *
     * So /sitemap.xml stays a plain urlset while everything fits, because an
     * index for 500 URLs is ceremony a crawler has to follow for nothing. Above
     * the cap it becomes a <sitemapindex> pointing at /sitemap-1.xml,
     * /sitemap-2.xml … and each shard is a urlset of at most SHARD urls. The
     * shelves ride in the first shard, so the structure of the shop is found
     * without reading to the end.
     */
    private static final int SHARD = 50_000;

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap(HttpServletRequest request) {
        if (!visibility().crawlable()) {
            throw new NotFoundException("this operator is not visible to crawlers");
        }
        List<PublicOffering> rows = shelf(request);
        int urls = rows.size() + SHELVES.size();
        if (urls > SHARD) {
            String base = baseUrl(request);
            int shards = (urls + SHARD - 1) / SHARD;
            StringBuilder index = new StringBuilder(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
            for (int i = 1; i <= shards; i++) {
                index.append("  <sitemap><loc>").append(esc(base + "/sitemap-" + i + ".xml"))
                        .append("</loc></sitemap>\n");
            }
            index.append("</sitemapindex>\n");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(index.toString());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .body(urlset(request, rows, 1));
    }

    /** One shard of a sharded sitemap. 1-based, as the index names them. */
    @GetMapping(value = "/sitemap-{shard}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemapShard(@PathVariable("shard") int shard,
                                               HttpServletRequest request) {
        if (!visibility().crawlable()) {
            throw new NotFoundException("this operator is not visible to crawlers");
        }
        List<PublicOffering> rows = shelf(request);
        if (shard < 1 || (long) (shard - 1) * SHARD >= rows.size() + SHELVES.size()) {
            throw new NotFoundException("no such sitemap shard");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .body(urlset(request, rows, shard));
    }

    /**
     * The urls of one shard (shard 1 is the whole thing when nothing is sharded).
     *
     * ONE RULE decides membership. This used to list every Active offering,
     * which is not the same question: an offering past its window, or sold only
     * through a dealer, was advertised to search engines and then refused by the
     * page the crawler followed.
     *
     * THE SHELVES FIRST (#180). Category pages are real URLs, and a sitemap that
     * lists only products gives a crawler no way to understand how the shop is
     * organised — just a flat list of things. They sit at the front so they land
     * in the first shard.
     */
    private String urlset(HttpServletRequest request, List<PublicOffering> rows, int shard) {
        String base = baseUrl(request);
        List<String> locs = new java.util.ArrayList<>(rows.size() + SHELVES.size());
        List<String> mods = new java.util.ArrayList<>(rows.size() + SHELVES.size());
        for (String shelfSlug : SHELVES) {
            locs.add(base + "/shop/category/" + shelfSlug);
            mods.add(null);
        }
        for (PublicOffering view : rows) {
            locs.add(view.canonicalUrl());
            mods.add(view.lastUpdate() == null ? null : view.lastUpdate().toLocalDate().toString());
        }
        int from = (shard - 1) * SHARD;
        int to = Math.min(locs.size(), from + SHARD);
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (int i = from; i < to; i++) {
            sb.append("  <url><loc>").append(esc(locs.get(i))).append("</loc>");
            if (mods.get(i) != null) {
                sb.append("<lastmod>").append(esc(mods.get(i))).append("</lastmod>");
            }
            sb.append("</url>\n");
        }
        sb.append("</urlset>\n");
        return sb.toString();
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        // the lever crawlers actually obey — the tenant's posture decides WHICH
        // crawlers are named; CrawlerPolicy knows what each one is for
        Visibility v = visibility();
        ResponseEntity.BodyBuilder res = ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN);
        if (v.noindex()) {
            // Disallow asks a crawler not to FETCH; it does not stop a URL
            // somebody else links to from being listed. The tenant said dark.
            res.header("X-Robots-Tag", CrawlerPolicy.NOINDEX);
        }
        return res.body(crawlers.robotsTxt(v));
    }

    @GetMapping(value = "/llms.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> llms(HttpServletRequest request) {
        // honest label: speculative courtesy for LLM crawlers — adoption is
        // real (~10% of sites) but bot consumption is negligible today; the
        // load-bearing GEO work is the bot-readable pages and robots.txt
        if (!visibility().publishesLlmsTxt()) {
            throw new NotFoundException(
                    "llms.txt is published where AI answer engines are welcome");
        }
        StringBuilder sb = new StringBuilder("# " + brand() + "\n\n> A telecom operator. "
                + "Offerings below are live catalog data; each links to a crawlable page "
                + "with schema.org Product/Offer markup.\n\n## Offerings\n\n");
        for (PublicOffering view : shelf(request)) {
            sb.append("- [").append(view.name()).append("](").append(view.canonicalUrl()).append(")");
            // the price an answer engine quotes is the one every other surface
            // publishes, because it is the same projection that computed it
            if (view.priced()) {
                sb.append(" — ").append(view.headline().text());
                if (view.upfront() != null) {
                    sb.append(" plus ").append(view.upfront().text()).append(" once");
                }
            }
            if (view.description() != null) {
                sb.append(": ").append(summarise(view.description()));
            }
            sb.append("\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sb.toString());
    }

    /**
     * The shelf both list surfaces publish: everything sellable on the web,
     * from the one projection. They show the same offerings because they answer
     * the same question; the agentic feed asks it for its own channel and drops
     * what it cannot price, which is a difference on purpose.
     */
    private List<PublicOffering> shelf(HttpServletRequest request) {
        TenantRegistry.TenantEntry tenant = tenant();
        return catalog.sellable(Channels.DEFAULT, currency(tenant), baseUrl(request));
    }

    /**
     * A description short enough for a list, cut at a word rather than through
     * one. The old cut landed mid-word ("…one bill, bun"), which reads as a
     * broken feed to the readers this file exists for.
     */
    private static String summarise(String description) {
        if (description.length() <= 160) {
            return description;
        }
        int cut = description.lastIndexOf(' ', 160);
        return description.substring(0, cut < 80 ? 160 : cut) + "…";
    }

    /* ---------- helpers ---------- */

    /**
     * The language the operator sells in, from the resolved tenant (tenants.yml
     * `locale` — the same value the shop's own channel config carries). The page
     * used to declare `en` for every tenant, including the Norwegian ones.
     */
    private String language(TenantRegistry.TenantEntry tenant) {
        return tenant == null || tenant.getLocale() == null || tenant.getLocale().isBlank()
                ? "und" : tenant.getLocale();
    }

    /** The money the operator prices in, used only where a price names no unit. */
    private String currency(TenantRegistry.TenantEntry tenant) {
        return tenant == null || tenant.getCurrency() == null || tenant.getCurrency().isBlank()
                ? null : tenant.getCurrency();
    }

    /**
     * Scheme and host as the VISITOR sees them, for absolute canonical and image
     * URLs. Only the gateway's X-Forwarded headers can answer this: the
     * component's own Host header is an internal service name behind the
     * gateway, and publishing that would be worse than publishing a relative
     * URL — so an unforwarded request keeps relative URLs.
     */
    private String baseUrl(HttpServletRequest request) {
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            return "";
        }
        host = host.split(",")[0].trim();
        // These values end up in an href and in canonical/JSON-LD URLs, and
        // they arrive in a REQUEST HEADER. Escaping is not enough there: a
        // scheme of "javascript" survives every entity escape and still runs.
        // So the scheme is chosen from a closed set, never echoed, and a host
        // that is not a plain host[:port] is refused back to relative URLs.
        if (!SAFE_HOST.matcher(host).matches()) {
            return "";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        String first = proto == null ? "" : proto.split(",")[0].trim();
        String scheme = "https".equalsIgnoreCase(first) ? "https" : "http";
        return scheme + "://" + host;
    }

    /**
     * The storefront's lines of business, as the shop groups them. Duplicated
     * from apps/storefront/src/pages/lines.jsx, because a sitemap is generated
     * by the catalog and the shop is a separate deployable — there is no shared
     * module between them today. The honest cost of that is this list; the fix
     * is the storefront generating its own sitemap once it renders server-side,
     * which is the rest of #180.
     */
    private static final List<String> SHELVES = List.of(
            "bundles", "mobile", "internet", "tv", "devices", "security", "top-ups");

    /** host or host:port — letters, digits, dots, hyphens; nothing that could carry a scheme. */
    private static final java.util.regex.Pattern SAFE_HOST =
            java.util.regex.Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");

    /**
     * Spring's escaper, not a hand-rolled one. The four-replace version missed
     * the single quote — harmless inside a double-quoted attribute, wrong the
     * moment anyone writes one in single quotes — and a static analyser cannot
     * tell a home-made escaper from no escaper at all, so it read every
     * attribute here as unescaped. One known-good implementation answers both.
     */
    private String esc(String s) {
        return s == null ? "" : org.springframework.web.util.HtmlUtils.htmlEscape(s);
    }
}
