package com.bss.catalog.controller;

import com.bss.catalog.dto.AcpProductFeed;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.seo.PublicCatalog;
import com.bss.catalog.seo.PublicOffering;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * The Agentic Commerce Protocol product feed: the tenant's sellable shelf in
 * the shape shopping agents (ChatGPT, Perplexity, any ACP consumer) ingest to
 * discover and compare offerings.
 *
 * <p>An <b>adapter over {@link PublicCatalog}</b>, like every other public
 * face. It used to assemble TMF620 for itself, and the arithmetic drifted: the
 * feed led with the first one-time price, so a triple-play bundle was sold to
 * shopping agents at <b>49.00</b> — its fibre installation fee — while its own
 * crawlable page correctly said <b>64.98 a month</b>. Same catalog rows, two
 * answers, and the wrong one was the one an AI agent quoted.</p>
 *
 * <p>The wire contract is unchanged: the same fields, the same names, the same
 * order. What changed is that the numbers in them are now the same numbers
 * every other surface publishes.</p>
 *
 * <p>The gateway's AgentCommerceGateFilter is the authoritative per-tenant
 * switch (off|discovery|full); the check here is defense in depth.</p>
 */
@RestController
@RequestMapping("/acp")
public class AcpFeedController {

    /** The channel this surface speaks for: an offer may be sold on the web and withheld here. */
    private static final String CHANNEL = "agent-acp";

    private final PublicCatalog catalog;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public AcpFeedController(PublicCatalog catalog, TenantRegistry tenants, TenantScope tenantScope) {
        this.catalog = catalog;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/product_feed")
    public AcpProductFeed productFeed(@RequestParam(name = "id", required = false) String onlyId) {
        TenantRegistry.TenantEntry tenant = requireExposed();
        List<AcpProductFeed.Item> products = new ArrayList<>();
        for (PublicOffering view : catalog.sellable(CHANNEL, currency(tenant), "")) {
            if (onlyId != null && !onlyId.equals(view.id())) {
                continue;
            }
            // a feed row an agent cannot price is noise, not reach — the one
            // place this surface deliberately shows less than the sitemap
            if (!view.priced()) {
                continue;
            }
            products.add(item(view));
        }
        return new AcpProductFeed(products);
    }

    /** Package-private so the one-projection test can hold this face beside the others. */
    static AcpProductFeed.Item item(PublicOffering view) {
        PublicOffering.Charge headline = view.headline();
        return new AcpProductFeed.Item(
                view.id(), view.name(), view.description(), view.category(), view.canonicalUrl(),
                view.availability().acp(),
                new AcpProductFeed.Price(headline.plain(), headline.currency()),
                headline.type(), headline.period(),
                view.bundle() ? Boolean.TRUE : null);
    }

    private TenantRegistry.TenantEntry requireExposed() {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        String mode = tenant == null || tenant.getAgentCommerce() == null
                ? "off" : tenant.getAgentCommerce();
        if ("off".equals(mode)) {
            throw new NotFoundException("no agentic commerce surface here");
        }
        return tenant;
    }

    /** The money the operator prices in, used only where a price names no unit. */
    private static String currency(TenantRegistry.TenantEntry tenant) {
        return tenant == null || tenant.getCurrency() == null || tenant.getCurrency().isBlank()
                ? "EUR" : tenant.getCurrency();
    }
}
