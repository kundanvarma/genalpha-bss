package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * PATCH /spendPolicy/{meterType}. {@code limit} is a {@link JsonNode} because
 * absent and explicit null differ: absent leaves the limit alone, JSON null
 * clears it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpendPolicyPatch(JsonNode limit, String currency, Boolean enabled, BigDecimal notifyAtPct,
        Boolean blockOnBreach, Boolean barred) {

    public boolean hasLimit() {
        return limit != null;
    }

    public BigDecimal limitValue() {
        return limit == null || limit.isNull() ? null : new BigDecimal(limit.asText());
    }
}
