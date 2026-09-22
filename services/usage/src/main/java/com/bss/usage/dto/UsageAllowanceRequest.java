package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** POST /usageAllowance: {productOffering{id}, usageType, allowance{value,units}, overagePrice{value,unit}, boost?, overageTier?}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageAllowanceRequest(JsonNode productOffering, String usageType, UnitValue allowance,
        Money overagePrice, Boolean boost, List<Tier> overageTier) {

    public String offeringId() {
        return productOffering != null && productOffering.hasNonNull("id") ? productOffering.get("id").asText() : null;
    }
}
