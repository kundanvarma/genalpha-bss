package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** TMF687 ReserveProductStock: the posted body, answered with the server's overlay. */
@JsonPropertyOrder({"id", "href", "reserveProductStockState", "reserveProductStockItem", "@type"})
public record ReserveProductStockView(
        @JsonProperty("id") String id,
        @JsonProperty("href") String href,
        @JsonProperty("reserveProductStockState") String reserveProductStockState,
        @JsonProperty("reserveProductStockItem") JsonNode reserveProductStockItem,
        @JsonProperty("@type") String type,
        @JsonAnyGetter Map<String, JsonNode> extensions) {

    public static final Set<String> DECLARED = Set.of("id", "href", "reserveProductStockState",
            "reserveProductStockItem", "@type");

    public ReserveProductStockView {
        extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }
}
