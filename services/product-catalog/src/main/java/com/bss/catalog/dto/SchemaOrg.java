package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * schema.org structured data as TYPES, so the crawler-facing document is
 * SERIALISED and never assembled. The generator used to concatenate 22 string
 * fragments with its own escaping, which meant the first product name carrying
 * a quote, a backslash or a newline published JSON a crawler rejects — and a
 * rejected document is invisible, not merely untidy.
 *
 * Nothing here is authored: every field is a projection of TMF620 (name,
 * description, category, attachments, specification characteristics, prices)
 * plus the tenant's own resolved configuration. There are deliberately no
 * reviews and no ratings: this page displays none, so claiming them would be
 * fabricated structured data — the kind search engines penalise.
 *
 * @see com.bss.catalog.mapper.JsonLd the writer that escapes it for a
 *      {@code <script>} element
 */
public final class SchemaOrg {

    public static final String CONTEXT = "https://schema.org";

    private SchemaOrg() {
    }

    /** A product page's one structured-data document. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"@context", "@type", "name", "description", "category", "sku", "url",
            "image", "brand", "additionalProperty", "offers"})
    public record Product(
            @JsonProperty("@context") String context,
            @JsonProperty("@type") String type,
            String name,
            String description,
            String category,
            String sku,
            String url,
            List<String> image,
            Organization brand,
            List<PropertyValue> additionalProperty,
            Offer offers) {

        public Product {
            context = context == null ? CONTEXT : context;
            type = type == null ? "Product" : type;
        }
    }

    /** The selling operator — the tenant's brand name, never a hostname or an id. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"@type", "name"})
    public record Organization(@JsonProperty("@type") String type, String name) {

        public static Organization named(String name) {
            return new Organization("Organization", name);
        }
    }

    /**
     * One truthful headline price. A subscription's headline is its RECURRING
     * charge, with the billing period declared beside it in a
     * {@code UnitPriceSpecification} so "39.99" is never read as a one-off;
     * the full breakdown (activation fees, early-termination) belongs in the
     * richer feeds, not here.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"@type", "url", "price", "priceCurrency", "availability", "priceSpecification"})
    public record Offer(
            @JsonProperty("@type") String type,
            String url,
            String price,
            String priceCurrency,
            String availability,
            UnitPrice priceSpecification) {

        public Offer {
            type = type == null ? "Offer" : type;
        }
    }

    /** A recurring charge: the amount, and the period it recurs over. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"@type", "price", "priceCurrency", "billingDuration", "unitCode"})
    public record UnitPrice(
            @JsonProperty("@type") String type,
            String price,
            String priceCurrency,
            Integer billingDuration,
            String unitCode) {

        public UnitPrice {
            type = type == null ? "UnitPriceSpecification" : type;
        }
    }

    /** A specification characteristic projected as a structured property. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonPropertyOrder({"@type", "name", "value", "unitText", "description"})
    public record PropertyValue(
            @JsonProperty("@type") String type,
            String name,
            String value,
            String unitText,
            String description) {

        public PropertyValue {
            type = type == null ? "PropertyValue" : type;
        }

        public static PropertyValue of(String name, String value, String unitText, String description) {
            return new PropertyValue("PropertyValue", name, value, unitText, description);
        }
    }
}
