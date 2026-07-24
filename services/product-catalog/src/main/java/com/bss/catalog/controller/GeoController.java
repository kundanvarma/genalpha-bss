package com.bss.catalog.controller;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.service.ProductOfferingPriceService;
import com.bss.catalog.service.ProductOfferingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GENERATIVE DISCOVERABILITY (GEO): the crawler-facing face of the shop.
 * AI answer engines (GPTBot, ClaudeBot, PerplexityBot…) do not execute
 * JavaScript, so the SPA is invisible to them — these endpoints render the
 * SAME catalog facts as complete HTML with schema.org JSON-LD, generated
 * live from TMF620 (never authored, never synced; the suite proves the
 * bot price equals the catalog price). The gateway dual-serves by
 * User-Agent: humans get the SPA, crawlers get this.
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
    private final ProductOfferingPriceService prices;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public GeoController(ProductOfferingService offerings, ProductOfferingPriceService prices,
            TenantRegistry tenants, TenantScope tenantScope) {
        this.offerings = offerings;
        this.prices = prices;
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
    public ResponseEntity<String> offering(@PathVariable("id") String id) {
        if ("dark".equals(visibility())) {
            throw new NotFoundException("this operator is not visible to crawlers");
        }
        ProductOfferingDto o = offerings.findById(id);
        ProductOfferingPriceDto p = pickPrice(o);
        String name = esc(o.getName());
        String desc = esc(o.getDescription() == null ? "" : o.getDescription());
        String amount = p == null ? null : String.valueOf(p.getPrice().get("value"));
        String currency = p == null ? "EUR" : String.valueOf(p.getPrice().getOrDefault("unit", "EUR"));
        String category = o.getCategory() == null || o.getCategory().isEmpty()
                ? "" : esc(String.valueOf(o.getCategory().get(0).get("name")));

        String jsonLd = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\","
                + "\"name\":\"" + name + "\",\"description\":\"" + desc + "\","
                + (category.isEmpty() ? "" : "\"category\":\"" + category + "\",")
                + "\"brand\":{\"@type\":\"Organization\",\"name\":\"" + esc(brand()) + "\"}"
                + (amount == null ? "" : ",\"offers\":{\"@type\":\"Offer\",\"price\":\"" + amount
                        + "\",\"priceCurrency\":\"" + currency
                        + "\",\"availability\":\"https://schema.org/InStock\"}")
                + "}";

        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>" + name + " — " + esc(brand()) + "</title>"
                + "<meta name=\"description\" content=\"" + desc + "\">"
                + "<link rel=\"canonical\" href=\"/shop/offering/" + esc(id) + "\">"
                + "<script type=\"application/ld+json\">" + jsonLd + "</script>"
                + "</head><body>"
                + "<h1>" + name + "</h1>"
                + (category.isEmpty() ? "" : "<p>Category: " + category + "</p>")
                + "<p>" + desc + "</p>"
                + (amount == null ? "" : "<p>Price: <b>" + amount + " " + currency + "</b></p>")
                + "<p>Sold by " + esc(brand()) + ". <a href=\"/shop/offering/" + esc(id)
                + "\">View in the shop</a></p>"
                + "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
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

    private ProductOfferingPriceDto pickPrice(ProductOfferingDto offering) {
        List<Map<String, Object>> refs = offering.getProductOfferingPrice();
        if (refs == null) {
            return null;
        }
        Map<String, ProductOfferingPriceDto> index = prices.findAll(0, 500, Map.of()).items()
                .stream().collect(Collectors.toMap(ProductOfferingPriceDto::getId,
                        Function.identity(), (a, b) -> a));
        List<ProductOfferingPriceDto> resolved = refs.stream()
                .map(ref -> {
                    ProductOfferingPriceDto hit = index.get(String.valueOf(ref.get("id")));
                    if (hit != null) {
                        return hit;
                    }
                    if (ref.get("price") instanceof Map<?, ?> p && p.get("value") != null) {
                        ProductOfferingPriceDto dto = new ProductOfferingPriceDto();
                        dto.setId(String.valueOf(ref.get("id")));
                        dto.setPriceType(String.valueOf(ref.getOrDefault("priceType", "oneTime")));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pm = (Map<String, Object>) p;
                        dto.setPrice(pm);
                        return dto;
                    }
                    return null;
                })
                .filter(p -> p != null && p.getPrice() != null && p.getPrice().get("value") != null)
                .filter(p -> p.getProdSpecCharValueUse() == null || p.getProdSpecCharValueUse().isEmpty())
                .toList();
        return resolved.stream().filter(p -> "oneTime".equals(p.getPriceType())).findFirst()
                .orElseGet(() -> resolved.stream().findFirst().orElse(null));
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
