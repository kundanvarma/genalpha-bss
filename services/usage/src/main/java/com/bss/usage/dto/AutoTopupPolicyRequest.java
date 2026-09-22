package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * PUT /autoTopupPolicy. Enabling requires {@code consent:true} in the same
 * request. {@code maxSpendPerCycle} is a {@link JsonNode}: absent leaves the
 * cap alone, JSON null removes it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AutoTopupPolicyRequest(Boolean enabled, Boolean consent, String boostOfferingId, String trigger,
        BigDecimal triggerPct, Integer maxBoostsPerCycle, JsonNode maxSpendPerCycle) {

    public boolean enable() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean consented() {
        return Boolean.TRUE.equals(consent);
    }

    public boolean hasMaxSpendPerCycle() {
        return maxSpendPerCycle != null;
    }

    public BigDecimal maxSpendPerCycleValue() {
        return maxSpendPerCycle == null || maxSpendPerCycle.isNull() ? null
                : new BigDecimal(maxSpendPerCycle.asText());
    }
}
