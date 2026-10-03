package com.bss.catalog.controller;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.SchemaOrg;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.mapper.JsonLd;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
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
 * The per-tenant `ai-visibility` switch (open | search-only | dark)
 * drives robots.txt — the lever crawlers actually obey. llms.txt ships
 * for `open` tenants, honestly labeled: it is speculative courtesy, not
 * the feature.
 */
@RestController
@RequestMapping("/seo")
public class GeoController {

    private final ProductOfferingService offerings;
    private final SchemaOrgProjection projection;
    private final JsonLd jsonLd;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public GeoController(ProductOfferingService offerings, SchemaOrgProjection projection,
            JsonLd jsonLd, TenantRegistry tenants, TenantScope tenantScope) {
        this.offerings = offerings;
        this.projection = projection;
        this.jsonLd = jsonLd;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    private TenantRegistry.TenantEntry tenant() {
        return tenants.byId(tenantScope.currentTenantId());
    }

    private String visibility() {
        TenantRegistry.TenantEntry t = tenant();
        return t == null || t.getAiVisibility() == null ? "search-only" : t.getAiVisibility();
    }

    private String brand() {
        TenantRegistry.TenantEntry t = tenant();
        return t == null || t.getBrandName() == null ? "the operator" : t.getBrandName();
    }

    /* ---------- the bot-readable offering page ---------- */

    @GetMapping(value = "/offering/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> offering(@PathVariable("id") String id, HttpServletRequest request) {
        if ("dark".equals(visibility())) {
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

    /* ---------- sitemap / robots / llms.txt ---------- */

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        if ("dark".equals(visibility())) {
            throw new NotFoundException("this operator is not visible to crawlers");
        }
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (ProductOfferingDto o : offerings.findAll(0, 500,
                Map.of("lifecycleStatus", "Active")).items()) {
            sb.append("  <url><loc>/shop/offering/").append(esc(o.getId()))
                    .append("</loc></url>\n");
        }
        sb.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(sb.toString());
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        // the lever crawlers actually obey — driven by the tenant's switch
        String v = visibility();
        String body;
        if ("dark".equals(v)) {
            body = "User-agent: *\nDisallow: /\n";
        } else if ("open".equals(v)) {
            body = "User-agent: *\nAllow: /\n\nSitemap: /sitemap.xml\n";
        } else { // search-only: classic search yes, AI answer/training bots no
            StringBuilder sb = new StringBuilder();
            for (String bot : List.of("GPTBot", "OAI-SearchBot", "ClaudeBot", "anthropic-ai",
                    "PerplexityBot", "Google-Extended", "CCBot", "Bytespider")) {
                sb.append("User-agent: ").append(bot).append("\nDisallow: /\n\n");
            }
            sb.append("User-agent: *\nAllow: /\n\nSitemap: /sitemap.xml\n");
            body = sb.toString();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    @GetMapping(value = "/llms.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> llms() {
        // honest label: speculative courtesy for LLM crawlers — adoption is
        // real (~10% of sites) but bot consumption is negligible today; the
        // load-bearing GEO work is the bot-readable pages and robots.txt
        if (!"open".equals(visibility())) {
            throw new NotFoundException("llms.txt is published by open tenants only");
        }
        StringBuilder sb = new StringBuilder("# " + brand() + "\n\n> A telecom operator. "
                + "Offerings below are live catalog data; each links to a crawlable page "
                + "with schema.org Product/Offer markup.\n\n## Offerings\n\n");
        for (ProductOfferingDto o : offerings.findAll(0, 200,
                Map.of("lifecycleStatus", "Active")).items()) {
            sb.append("- [").append(o.getName()).append("](/shop/offering/")
                    .append(o.getId()).append(")");
            if (o.getDescription() != null) {
                String d = o.getDescription();
                sb.append(": ").append(d.length() > 120 ? d.substring(0, 120) : d);
            }
            sb.append("\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sb.toString());
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
        String proto = request.getHeader("X-Forwarded-Proto");
        return (proto == null || proto.isBlank() ? "http" : proto.split(",")[0].trim())
                + "://" + host.split(",")[0].trim();
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
