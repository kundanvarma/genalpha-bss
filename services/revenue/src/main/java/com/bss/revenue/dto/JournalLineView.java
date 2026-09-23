package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One side of a posting, at the scale it was stored. */
@JsonPropertyOrder({"seq", "accountCode", "accountName", "debit", "credit", "ref", "description"})
public record JournalLineView(
        @JsonProperty("seq") int seq,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("accountName") String accountName,
        @JsonProperty("debit") BigDecimal debit,
        @JsonProperty("credit") BigDecimal credit,
        @JsonProperty("ref") String ref,
        @JsonProperty("description") String description) {
}
