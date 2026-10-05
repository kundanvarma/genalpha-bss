package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * THE FACTUAL DISCOVERY SURFACE: what a generative engine, a shopping agent or
 * a partner reads instead of scraping a rendered page.
 *
 * <p>Three rules make this a discovery feed rather than a marketing feed, and
 * they are the reason the shape differs from the agentic-commerce one:</p>
 *
 * <ul>
 * <li><b>Every fact traces back.</b> {@link Provenance} carries the catalog ids
 *     a reviewer or an agent can type into the API to check any claim here. A
 *     price with no traceable source is marketing.</li>
 * <li><b>The whole price, not one number.</b> A bundle's cost is a list of
 *     components with their periods, not a headline an agent has to guess at.
 *     The agentic feed can only carry one number; this one does not have to.</li>
 * <li><b>Absence is a value.</b> A fact the specification does not declare is
 *     missing from the response rather than defaulted. Nothing here is
 *     synthesised.</li>
 * </ul>
 *
 * <p>{@link #generatedAt} and each product's freshness are not decoration: an
 * agent that cannot tell how old a price is will quote a withdrawn one with
 * confidence.</p>
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "tenant", "generatedAt", "productCount", "truncated", "products"})
public record DiscoveryFeed(String version, String tenant, String generatedAt,
        int productCount, Boolean truncated, List<Product> products) {

    /** The wire version. Bumped when the shape changes in a way a reader would notice. */
    public static final String VERSION = "1.0";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "description", "category", "url", "bundle",
            "availability", "price", "facts", "freshness", "provenance"})
    public record Product(String id, String name, String description, String category,
            String url, Boolean bundle, String availability, Price price,
            List<Fact> facts, Freshness freshness, Provenance provenance) {
    }

    /**
     * The whole commercial picture: what is paid regularly, what is paid once,
     * and every component behind both. {@code headline} is the figure a surface
     * leads with; {@code components} is what it is made of.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"headline", "upfront", "components"})
    public record Price(Charge headline, Charge upfront, List<Charge> components) {
    }

    /** One charge. {@code period} is absent when it is paid once. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"type", "amount", "currency", "period", "name"})
    public record Charge(String type, String amount, String currency, String period, String name) {
    }

    /** One declared fact. {@code unit} and {@code description} appear only where the author wrote them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "value", "unit", "description"})
    public record Fact(String name, String value, String unit, String description) {
    }

    /** How old this is, and how long it is good for. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"lastUpdate", "validFrom", "validTo"})
    public record Freshness(String lastUpdate, String validFrom, String validTo) {
    }

    /**
     * The catalog records every fact above was read from. Not optional: it is
     * what separates a feed an agent can be held to from one it cannot.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"offeringId", "specificationId", "priceIds", "api"})
    public record Provenance(String offeringId, String specificationId, List<String> priceIds,
            String api) {

        /** Where a reader goes to check any of it. */
        public static final String API = "/tmf-api/productCatalogManagement/v4";
    }
}
