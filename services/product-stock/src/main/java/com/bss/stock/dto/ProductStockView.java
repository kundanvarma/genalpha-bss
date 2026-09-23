package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * TMF687 ProductStock: the caller's own document, answered with the server's
 * overlay. The keys the service computes or manages are declared; everything
 * else the poster wrote rides in {@code extensions} so it round-trips — which
 * is why the declared keys now come first and the stored ones after.
 *
 * <p>Three of the declared keys are the STANDARD's mandatory attributes, and a
 * posted value for them wins over the computed default — so they stay trees.
 */
@JsonPropertyOrder({"id", "href", "name", "stockedQuantity", "reservedQuantity", "availableQuantity",
        "productStockLevel", "productStockStatusType", "stockedProduct", "lastUpdate", "@type"})
public record ProductStockView(
        @JsonProperty("id") String id,
        @JsonProperty("href") String href,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("name") String name,
        @JsonProperty("stockedQuantity") Quantity stockedQuantity,
        @JsonProperty("reservedQuantity") Quantity reservedQuantity,
        @JsonProperty("availableQuantity") Quantity availableQuantity,
        @JsonProperty("productStockLevel") JsonNode productStockLevel,
        @JsonProperty("productStockStatusType") JsonNode productStockStatusType,
        @JsonProperty("stockedProduct") JsonNode stockedProduct,
        @JsonProperty("lastUpdate") OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type,
        @JsonAnyGetter Map<String, JsonNode> extensions) {

    /** The keys the view declares — everything else in the stored body is an extension. */
    public static final Set<String> DECLARED = Set.of("id", "href", "name", "stockedQuantity",
            "reservedQuantity", "availableQuantity", "productStockLevel", "productStockStatusType",
            "stockedProduct", "lastUpdate", "@type");

    public ProductStockView {
        extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }
}
