package com.bss.intelligence.risk;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The TMF696 assessment request as the engine reads it: whose risk, and —
 * for an order — the order's shape and what only the caller knows about the
 * session. A bare relatedParty object is accepted beside the standard list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskAssessmentRequest(
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<PartyRef> relatedParty,
        Object totalQuantity,
        Boolean verifiedIdentity,
        String addressSource) {
}
