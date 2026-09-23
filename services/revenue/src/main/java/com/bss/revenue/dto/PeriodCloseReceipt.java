package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The completeness attestation. */
@JsonPropertyOrder({"closedThrough", "note"})
public record PeriodCloseReceipt(
        @JsonProperty("closedThrough") String closedThrough,
        @JsonProperty("note") String note) {

    public static PeriodCloseReceipt of(String closedThrough) {
        return new PeriodCloseReceipt(closedThrough,
                "postings for bills dated on or before this refuse; the export is final");
    }
}
