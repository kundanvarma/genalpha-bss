package com.bss.catalog.controller;

import com.bss.catalog.dto.DiscoveryFeed;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.seo.PublicCatalog;
import com.bss.catalog.seo.PublicOffering;
import com.bss.catalog.service.Channels;
import com.bss.catalog.service.ProductOfferingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * A FACTUAL DISCOVERY SURFACE for generative engines, shopping agents and
 * partners — the thing they read instead of scraping a rendered page.
 *
 * <p>It is a third face on {@link PublicCatalog}, beside the crawler page and
 * the agentic-commerce feed, and that is the whole point: the same projection
 * answers all three, so an agent reading here and an agent reading
 * {@code /acp/product_feed} cannot be told different prices for the same
 * offering. The agentic feed's contract is untouched — it stays conformant and
 * carries one number, as its protocol requires; this surface carries the whole
 * price because it can.</p>
 *
 * <p><b>Every response is traceable.</b> Each product names the catalog records
 * it was read from, so any price here can be checked against TMF620 by a
 * reviewer or by the agent itself. That is the difference between a discovery
 * feed and a marketing feed, and it is the one that matters on the day an AI
 * quotes a price to a customer.</p>
 *
 * <p><b>Who sees it.</b> The tenant's own {@code ai-visibility} decides, exactly
 * as it decides robots.txt and llms.txt. A rich machine-readable catalogue is
 * also the easiest possible price-scraping surface for a competitor, so a
 * tenant that has said "classic search yes, AI no" ({@code search-only}) does
 * not get one — that posture would be meaningless if this door stayed open.</p>
 */
@RestController
@RequestMapping("/discovery/v1")
public class DiscoveryController {

    /** The channel this surface speaks for: the public web shelf. */
    private static final String CHANNEL = Channels.DEFAULT;

    private final PublicCatalog catalog;
    private final ProductOfferingService offerings;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public DiscoveryController(PublicCatalog catalog, ProductOfferingService offerings,
            TenantRegistry tenants, TenantScope tenantScope) {
        this.catalog = catalog;
        this.offerings = offerings;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/products")
    public DiscoveryFeed products(HttpServletRequest request) {
        TenantRegistry.TenantEntry tenant = requireVisible();
        List<DiscoveryFeed.Product> rows = catalog
                .sellable(CHANNEL, currency(tenant), baseUrl(request)).stream()
                .map(DiscoveryController::product)
                .toList();
        return feed(rows);
    }

    @GetMapping("/products/{id}")
    public DiscoveryFeed product(@PathVariable("id") String id, HttpServletRequest request) {
        TenantRegistry.TenantEntry tenant = requireVisible();
        ProductOfferingDto offering = offerings.findById(id);
        PublicOffering view = catalog.of(offering, currency(tenant), baseUrl(request));
        return feed(List.of(product(view)));
    }

    private DiscoveryFeed feed(List<DiscoveryFeed.Product> rows) {
        return new DiscoveryFeed(DiscoveryFeed.VERSION, tenantScope.currentTenantId(),
                OffsetDateTime.now().toString(), rows.size(), rows);
    }

    /* ---------- the projection, dressed for a machine reader ---------- */

    private static DiscoveryFeed.Product product(PublicOffering view) {
        return new DiscoveryFeed.Product(
                view.id(), view.name(), view.description(), view.category(), view.canonicalUrl(),
                view.bundle() ? Boolean.TRUE : null,
                view.availability().name().toLowerCase(Locale.ROOT),
                price(view),
                view.facts().stream()
                        .map(f -> new DiscoveryFeed.Fact(f.name(), f.value(), f.unit(), f.description()))
                        .toList(),
                freshness(view),
                new DiscoveryFeed.Provenance(view.provenance().offeringId(),
                        view.provenance().specificationId(), view.provenance().priceIds(),
                        DiscoveryFeed.Provenance.API));
    }

    /**
     * The whole price. An agentic feed can carry one number and must; this
     * surface carries the headline, what is paid once beside it, and every
     * component behind both — so an agent comparing a bundle does not have to
     * infer what the figure is made of.
     */
    private static DiscoveryFeed.Price price(PublicOffering view) {
        if (!view.priced()) {
            return null; // priced on application: say nothing rather than invent a number
        }
        return new DiscoveryFeed.Price(charge(view.headline()), charge(view.upfront()),
                view.charges().stream().map(DiscoveryController::charge).toList());
    }

    private static DiscoveryFeed.Charge charge(PublicOffering.Charge c) {
        return c == null ? null
                : new DiscoveryFeed.Charge(c.type(), c.plain(), c.currency(), c.period(), c.name());
    }

    private static DiscoveryFeed.Freshness freshness(PublicOffering view) {
        String lastUpdate = view.lastUpdate() == null ? null : view.lastUpdate().toString();
        PublicOffering.Window window = view.validFor();
        if (lastUpdate == null && window == null) {
            return null;
        }
        return new DiscoveryFeed.Freshness(lastUpdate,
                window == null ? null : window.startDateTime(),
                window == null ? null : window.endDateTime());
    }

    /* ---------- who may read it ---------- */

    private TenantRegistry.TenantEntry requireVisible() {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        if (!com.bss.catalog.seo.Visibility.of(tenant == null ? null : tenant.getAiVisibility())
                .servesDiscoveryFeed()) {
            throw new NotFoundException(
                    "this operator does not publish a machine-readable catalogue");
        }
        return tenant;
    }

    private static String currency(TenantRegistry.TenantEntry tenant) {
        return tenant == null || tenant.getCurrency() == null || tenant.getCurrency().isBlank()
                ? null : tenant.getCurrency();
    }

    /**
     * Absolute URLs where the gateway told us the host, relative where it did
     * not — the same rule the crawler page follows, and for the same reason: a
     * guessed host in a feed an agent will follow is worse than a relative one.
     */
    private static String baseUrl(HttpServletRequest request) {
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            return "";
        }
        host = host.split(",")[0].trim();
        if (!SAFE_HOST.matcher(host).matches()) {
            return "";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        String first = proto == null ? "" : proto.split(",")[0].trim();
        return ("https".equalsIgnoreCase(first) ? "https" : "http") + "://" + host;
    }

    private static final java.util.regex.Pattern SAFE_HOST =
            java.util.regex.Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
}
