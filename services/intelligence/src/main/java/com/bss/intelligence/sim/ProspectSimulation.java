package com.bss.intelligence.sim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * "Your business on this BSS" from a price list and an assumed base mix —
 * every number a stated assumption, and the report says so. id and name
 * arrive once the receipt is saved.
 */
@JsonPropertyOrder({"@type", "lines", "totalSubscribers", "annualRevenue", "annualWholesaleCostCeiling",
        "annualGrossMarginFloor", "currency", "assumptions", "id", "name"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProspectSimulation(
        @JsonProperty("@type") String type,
        List<Line> lines,
        long totalSubscribers,
        BigDecimal annualRevenue,
        BigDecimal annualWholesaleCostCeiling,
        BigDecimal annualGrossMarginFloor,
        String currency,
        List<String> assumptions,
        String id,
        String name) {

    @JsonPropertyOrder({"name", "subscribers", "monthlyPrice", "annualRevenue", "wholesaleCostCeilingPerSub",
            "marginPerSub"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String name, long subscribers, BigDecimal monthlyPrice, BigDecimal annualRevenue,
            BigDecimal wholesaleCostCeilingPerSub, BigDecimal marginPerSub) {
    }

    /** POST /simulate/prospect — a price list and an assumed base mix. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String name, List<Offering> offerings, BigDecimal wholesaleDataRatePerGb,
            String currency) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Offering(String name, BigDecimal monthlyPrice, Long subscribers, BigDecimal allowanceGb) {
        }
    }

    public ProspectSimulation saved(String id, String name) {
        return new ProspectSimulation(type, lines, totalSubscribers, annualRevenue, annualWholesaleCostCeiling,
                annualGrossMarginFloor, currency, assumptions, id, name);
    }
}
