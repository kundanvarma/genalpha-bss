package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A bank credit settled a bill. The RemittanceAppliedEvent payload and the back-office answer. */
@JsonPropertyOrder({"billNo", "amount", "relatedParty", "@type"})
public record RemittanceApplied(String billNo, String amount, List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) {
}
