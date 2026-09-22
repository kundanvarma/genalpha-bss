package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * "This charge is wrong", and what was decided about it. The resolve path
 * appends the bill's resulting state (the DisputeResolvedEvent carries it).
 */
@JsonPropertyOrder({"id", "billId", "billNo", "reason", "status", "creditAmount", "resolutionNote", "createdAt",
        "partyId", "relatedParty", "@type", "billState"})
public record DisputeView(String id, String billId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String billNo,
        String reason, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal creditAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String resolutionNote,
        String createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String partyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) String billState) {

    public DisputeView resolved(String billState) {
        return new DisputeView(id, billId, billNo, reason, status, creditAmount, resolutionNote, createdAt,
                partyId, relatedParty, type, billState);
    }
}
