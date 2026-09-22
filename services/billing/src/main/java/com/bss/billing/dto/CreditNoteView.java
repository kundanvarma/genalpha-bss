package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The numbered reversing document. Also the CreditNoteIssuedEvent payload.
 * {@code creditedLines} appears only for a per-line credit.
 */
@JsonPropertyOrder({"id", "href", "creditNoteNo", "billId", "billNo", "amount", "reason", "settlement", "refundRef",
        "disputeId", "creditedLines", "relatedParty", "issuedAt", "@type"})
public record CreditNoteView(String id, String href, String creditNoteNo, String billId, String billNo,
        Money amount, String reason, String settlement,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refundRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String disputeId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CreditedLine> creditedLines,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RelatedPartyRef> relatedParty,
        OffsetDateTime issuedAt,
        @JsonProperty("@type") String type) {
}
