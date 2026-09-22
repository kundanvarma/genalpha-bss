package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Create or patch a dunning policy: every field optional on a patch, the
 * merged result validated against the country pack. {@code steps} is the
 * ladder as written — stored verbatim, parsed when walked.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DunningPolicyRequest(String name, String country, Integer paymentTermDays,
        BigDecimal entryThreshold, String currency, JsonNode steps, BigDecimal reconnectionFee,
        BigDecimal writeOffThreshold, Integer promiseMaxPerPeriod, Integer promisePeriodDays,
        Integer promiseMaxDays, BigDecimal autoRefundThreshold, Boolean active) {

    /** A JSON null for steps means "not given", like an absent key. */
    public boolean hasSteps() {
        return steps != null && !steps.isNull();
    }
}
