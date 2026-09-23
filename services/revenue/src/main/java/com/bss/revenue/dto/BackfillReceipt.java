package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What the backfill did to one bill. */
@JsonPropertyOrder({"billId", "posted", "note"})
public record BackfillReceipt(
        @JsonProperty("billId") String billId,
        @JsonProperty("posted") boolean posted,
        @JsonProperty("note") String note) {

    public static BackfillReceipt of(String billId, boolean posted) {
        return new BackfillReceipt(billId, posted,
                posted ? "journal entry created" : "already journaled — nothing to do");
    }
}
