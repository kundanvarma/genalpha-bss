package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** Opt-in auto top-up: the policy as recorded, or the disabled shape {partyId, enabled:false} when none exists. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"partyId", "enabled", "boostOfferingId", "trigger", "triggerPct", "maxBoostsPerCycle",
        "maxSpendPerCycle", "consentAt", "@type"})
public record AutoTopupPolicyView(String partyId, boolean enabled, String boostOfferingId, String trigger,
        BigDecimal triggerPct, Integer maxBoostsPerCycle, BigDecimal maxSpendPerCycle, String consentAt,
        @JsonProperty("@type") String type) {

    public static AutoTopupPolicyView disabled(String partyId) {
        return new AutoTopupPolicyView(partyId, false, null, null, null, null, null, null, "AutoTopupPolicy");
    }
}
