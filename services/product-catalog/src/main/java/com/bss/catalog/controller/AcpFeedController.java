package com.bss.catalog.controller;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.exception.NotFoundException;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import com.bss.catalog.service.ProductOfferingPriceService;
import com.bss.catalog.service.ProductOfferingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Agentic Commerce Protocol product feed: the tenant's active catalog
 * projected into the structured shape shopping agents (ChatGPT, Perplexity,
 * any ACP consumer) ingest to discover and compare offerings. It is a
 * PROJECTION of TMF620 — no new data, no new authority: everything here is
 * already public on the catalog API. Pricing mirrors the storefront's honest
 * rule: the one-time charge is what an agent pays now; a recurring price is
 * shown as recurring — the first invoice bills the cycle, telecom-style.
 *
 * The gateway's AgentCommerceGateFilter is the authoritative per-tenant
 * switch (off|discovery|full); the check here is defense in depth.
 */
@RestController
@RequestMapping("/acp")
public class AcpFeedController {

    private final ProductOfferingService offerings;
    private final ProductOfferingPriceService prices;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public AcpFeedController(ProductOfferingService offerings, ProductOfferingPriceService prices,
            TenantRegistry tenants, TenantScope tenantScope) {
        this.offerings = offerings;
        this.prices = prices;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/product_feed")
    public Map<String, Object> productFeed(@RequestParam(name = "id", required = false) String onlyId) {
        requireExposed();
        Map<String, ProductOfferingPriceDto> priceIndex = prices.findAll(0, 500, Map.of()).items()
                .stream().collect(Collectors.toMap(ProductOfferingPriceDto::getId, Function.identity(),
                        (a, b) -> a));
        List<Map<String, Object>> products = new ArrayList<>();
        for (ProductOfferingDto offering : offerings.findAll(0, 500,
                Map.of("lifecycleStatus", "Active")).items()) {
            if (onlyId != null && !onlyId.equals(offering.getId())) {
                continue;
            }
            Map<String, Object> product = toFeedItem(offering, priceIndex);
            if (product != null) {
                products.add(product);
            }
        }
        return Map.of("products", products);
    }

    private void requireExposed() {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        String mode = tenant == null || tenant.getAgentCommerce() == null
                ? "off" : tenant.getAgentCommerce();
        if ("off".equals(mode)) {
            throw new NotFoundException("no agentic commerce surface here");
        }
    }

    /**
     * One feed row. The price is the first UNCONDITIONED price (a
     * characteristic-conditioned price depends on picks an agent has not
     * made), one-time preferred; an unpriced offering is skipped — a feed
     * row an agent cannot price is noise, not reach.
     */
    private Map<String, Object> toFeedItem(ProductOfferingDto offering,
            Map<String, ProductOfferingPriceDto> priceIndex) {
        ProductOfferingPriceDto price = pickPrice(offering, priceIndex);
        if (price == null || price.getPrice() == null || price.getPrice().get("value") == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", offering.getId());
        item.put("title", offering.getName());
        if (offering.getDescription() != null) {
            item.put("description", offering.getDescription());
        }
        List<Map<String, Object>> categories = offering.getCategory();
        if (categories != null && !categories.isEmpty()) {
            item.put("item_category", categories.get(0).get("name"));
        }
        item.put("link", "/shop/offering/" + offering.getId());
        item.put("availability", "in_stock");
        item.put("price", Map.of(
                "amount", String.valueOf(price.getPrice().get("value")),
                "currency", price.getPrice().getOrDefault("unit", "EUR")));
        item.put("price_type", price.getPriceType());
        if ("recurring".equals(price.getPriceType()) && price.getRecurringChargePeriodType() != null) {
            item.put("recurring_period", price.getRecurringChargePeriodType());
        }
        if (Boolean.TRUE.equals(offering.getIsBundle())) {
            item.put("is_bundle", true);
        }
        return item;
    }

    private ProductOfferingPriceDto pickPrice(ProductOfferingDto offering,
            Map<String, ProductOfferingPriceDto> priceIndex) {
        List<Map<String, Object>> refs = offering.getProductOfferingPrice();
        if (refs == null) {
            return null;
        }
        List<ProductOfferingPriceDto> resolved = refs.stream()
                .map(ref -> priceIndex.get(String.valueOf(ref.get("id"))))
                .filter(p -> p != null)
                .filter(p -> p.getProdSpecCharValueUse() == null || p.getProdSpecCharValueUse().isEmpty())
                .toList();
        return resolved.stream().filter(p -> "oneTime".equals(p.getPriceType())).findFirst()
                .orElseGet(() -> resolved.stream().findFirst().orElse(null));
    }
}
