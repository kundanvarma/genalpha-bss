package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * IMEI + guided condition answers → an instant estimate. The answers are the
 * customer's own document ({@code ageMonths} and the published defect
 * flags); it is stored and echoed as written, never re-shaped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeInQuoteRequest(
        String imei,
        String deviceRef,
        JsonNode conditionAnswers,
        String channel,
        String paymentRef,
        String agreementRef,
        List<RelatedPartyRef> relatedParty) {

    public String relatedPartyId() {
        return relatedParty == null || relatedParty.isEmpty() || relatedParty.get(0) == null
                ? null : relatedParty.get(0).id();
    }
}
