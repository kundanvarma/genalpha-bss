package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The price-rise letter, counted before it exists: cohort, exposure, money at risk. */
@JsonPropertyOrder({"@type", "offeringName", "risePercent", "currentMonthly", "newMonthly",
        "notificationLetters", "monthlyUpsideIfNobodyLeaves", "portOutExposureCustomers",
        "annualRevenueAtRisk", "assumptions"})
public record PriceRiseRehearsal(@JsonProperty("@type") String type, String offeringName,
        BigDecimal risePercent, BigDecimal currentMonthly, BigDecimal newMonthly,
        int notificationLetters, BigDecimal monthlyUpsideIfNobodyLeaves,
        int portOutExposureCustomers, BigDecimal annualRevenueAtRisk, List<String> assumptions) {
}
