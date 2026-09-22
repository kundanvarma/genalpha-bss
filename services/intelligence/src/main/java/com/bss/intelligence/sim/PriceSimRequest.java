package com.bss.intelligence.sim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** POST /simulate/priceChange — the proposed changes and, optionally, a
 * flat churn assumption; without one the measured baseline is the prior. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceSimRequest(String name, List<PriceChange> changes, BigDecimal assumedChurnPct) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceChange(String offeringName, BigDecimal newMonthlyPrice) {
    }
}
