package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** The migration rehearsal: legacy rows in, the exceptions by name out. */
public final class MigrationRehearsalDtos {

    private MigrationRehearsalDtos() {
    }

    /** A legacy export: rows, a tolerance (default 0.01), a name for the report. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(List<LegacyRow> rows, BigDecimal tolerance, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LegacyRow(String externalRef, String offeringName, BigDecimal expectedMonthly) {
    }

    /** One rehearsed row: priced against this catalog, or the offering is missing. */
    public sealed interface Row permits Priced, Missing {
    }

    @JsonPropertyOrder({"externalRef", "offeringName", "expectedMonthly", "currentMonthly", "delta"})
    public record Priced(String externalRef, String offeringName, BigDecimal expectedMonthly,
            BigDecimal currentMonthly, BigDecimal delta) implements Row {
    }

    @JsonPropertyOrder({"externalRef", "offeringName", "expectedMonthly", "reason"})
    public record Missing(String externalRef, String offeringName, BigDecimal expectedMonthly, String reason)
            implements Row {
    }

    @JsonPropertyOrder({"priceDiffers", "offeringMissing"})
    public record Exceptions(List<Priced> priceDiffers, List<Missing> offeringMissing) {
    }

    /** The report: counts, the exceptions, the assumptions; id and name once saved. */
    @JsonPropertyOrder({"@type", "rows", "matched", "priceDiffers", "offeringMissing", "readyToCutOver",
            "exceptions", "assumptions", "id", "name"})
    public record Report(@JsonProperty("@type") String type, int rows, int matched, int priceDiffers,
            int offeringMissing, boolean readyToCutOver, Exceptions exceptions, List<String> assumptions,
            @JsonInclude(JsonInclude.Include.NON_NULL) String id,
            @JsonInclude(JsonInclude.Include.NON_NULL) String name) {

        public Report saved(String id, String name) {
            return new Report(type, rows, matched, priceDiffers, offeringMissing, readyToCutOver, exceptions,
                    assumptions, id, name);
        }
    }

    /** A saved rehearsal in the list: the headline counts beside the stored report (kept as a tree). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "createdAt", "rows", "matched", "priceDiffers", "offeringMissing",
            "readyToCutOver", "report"})
    public record Summary(String id, String name, OffsetDateTime createdAt, JsonNode rows, JsonNode matched,
            JsonNode priceDiffers, JsonNode offeringMissing, JsonNode readyToCutOver, JsonNode report) {
    }
}
