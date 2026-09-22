package com.bss.intelligence.sim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * One receipt on the simulator shelf: the headline numbers lifted out of
 * the stored report (each null when that report kind does not carry it),
 * and the report itself as it was written. An unreadable stored report
 * still lists by name, with no headline keys at all.
 */
@JsonPropertyOrder({"id", "name", "createdAt", "totalAnnualRevenueDelta", "currency", "subscribersAtChurnRisk",
        "totalSubscribers", "annualRevenue", "annualGrossMarginFloor", "report"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedReport(
        String id,
        String name,
        OffsetDateTime createdAt,
        JsonNode totalAnnualRevenueDelta,
        JsonNode currency,
        JsonNode subscribersAtChurnRisk,
        JsonNode totalSubscribers,
        JsonNode annualRevenue,
        JsonNode annualGrossMarginFloor,
        JsonNode report) {

    /** A readable report: every headline present, as a JSON null when absent. */
    static SavedReport of(PriceSimReport row, JsonNode report) {
        return new SavedReport(row.getId(), row.getName(), row.getCreatedAt(),
                headline(report, "totalAnnualRevenueDelta"), headline(report, "currency"),
                headline(report, "subscribersAtChurnRisk"), headline(report, "totalSubscribers"),
                headline(report, "annualRevenue"), headline(report, "annualGrossMarginFloor"), report);
    }

    static SavedReport unreadable(PriceSimReport row) {
        return new SavedReport(row.getId(), row.getName(), row.getCreatedAt(),
                null, null, null, null, null, null, null);
    }

    private static JsonNode headline(JsonNode report, String key) {
        JsonNode value = report.get(key);
        return value == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : value;
    }
}
