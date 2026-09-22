package com.bss.intelligence.sim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * A price-change simulation: the money replayed against the real base,
 * assumptions and basis on its face. id and name arrive once the receipt
 * is saved (the stored copy has neither — it IS the receipt).
 */
@JsonPropertyOrder({"@type", "lines", "totalAnnualRevenueDelta", "currency", "subscribersAtChurnRisk",
        "assumptions", "basis", "id", "name"})
public record PriceSimReportView(
        @JsonProperty("@type") String type,
        List<Line> lines,
        BigDecimal totalAnnualRevenueDelta,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
        int subscribersAtChurnRisk,
        List<String> assumptions,
        Basis basis,
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) String name) {

    /** One changed offering: mechanical delta first, assumption-labeled second. */
    @JsonPropertyOrder({"offeringName", "subscribers", "currentMonthly", "proposedMonthly",
            "monthlyRevenueDelta", "annualRevenueDelta", "wholesaleCostCeilingPerSub", "marginPerSubBefore",
            "marginPerSubAfter", "subscribersAtChurnRisk", "annualRevenueDeltaWithAssumedChurn"})
    public record Line(
            String offeringName,
            int subscribers,
            BigDecimal currentMonthly,
            BigDecimal proposedMonthly,
            BigDecimal monthlyRevenueDelta,
            BigDecimal annualRevenueDelta,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal wholesaleCostCeilingPerSub,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal marginPerSubBefore,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal marginPerSubAfter,
            int subscribersAtChurnRisk,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal annualRevenueDeltaWithAssumedChurn) {
    }

    @JsonPropertyOrder({"activeProducts", "offeringsInCatalog", "asOf"})
    public record Basis(int activeProducts, int offeringsInCatalog, String asOf) {
    }

    /** The saved receipt's id and name, appended to the report. */
    public PriceSimReportView saved(String id, String name) {
        return new PriceSimReportView(type, lines, totalAnnualRevenueDelta, currency, subscribersAtChurnRisk,
                assumptions, basis, id, name);
    }

    /** The same report over a subset of its lines (a forecast over nobody is noise). */
    public PriceSimReportView withLines(List<Line> kept) {
        return new PriceSimReportView(type, kept, totalAnnualRevenueDelta, currency, subscribersAtChurnRisk,
                assumptions, basis, id, name);
    }
}
