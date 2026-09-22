package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Admin rule data: which offering includes how much of what, the price
 * beyond, and the stepped tier table the SOM pushes to the OCS. The
 * offering reference is stored as posted (an open block); {@code overageTier}
 * and {@code boost} appear only when set, as before.
 */
@JsonPropertyOrder({"id", "href", "usageType", "productOffering", "allowance", "overagePrice", "overageTier",
        "boost", "@type"})
public record UsageAllowanceView(String id, String href, String usageType, JsonNode productOffering,
        UnitValue allowance, Money overagePrice,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Tier> overageTier,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean boost,
        @JsonProperty("@type") String type) {
}
