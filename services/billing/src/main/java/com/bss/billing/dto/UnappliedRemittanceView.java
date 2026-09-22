package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Money the bank reported that no bill cleanly claims — one row of the AR worklist. */
@JsonPropertyOrder({"id", "batchRef", "reference", "amount", "reason", "receivedAt", "@type"})
public record UnappliedRemittanceView(String id, String batchRef, String reference, Money amount, String reason,
        String receivedAt, @JsonProperty("@type") String type) {
}
