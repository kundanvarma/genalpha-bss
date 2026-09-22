package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * A trade-in valuation as the shop, the app and the device desk read it:
 * estimate, grading outcome and delta, the offer's expiry, where the refund
 * went. {@code conditionAnswers} is the customer's own guided-assessment
 * document, stored and echoed as written. A quote carries the estimate's
 * {@code note}; a read carries the grading history when there is one.
 */
@JsonPropertyOrder({"id", "href", "imei", "deviceRef", "status", "estimatedValue", "finalValue", "delta",
        "currency", "offerExpiry", "channel", "conditionAnswers", "paymentRef", "refundRef", "agreementRef",
        "relatedParty", "@type", "note", "gradingEvent"})
public record TradeInValuationView(
        String id,
        String href,
        String imei,
        String deviceRef,
        String status,
        BigDecimal estimatedValue,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal finalValue,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal delta,
        String currency,
        String offerExpiry,
        @JsonInclude(JsonInclude.Include.NON_NULL) String channel,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode conditionAnswers,
        @JsonInclude(JsonInclude.Include.NON_NULL) String paymentRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refundRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String agreementRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<GradingEventView> gradingEvent) {

    public static final String TYPE = "TradeInValuation";

    public TradeInValuationView withNote(String note) {
        return new TradeInValuationView(id, href, imei, deviceRef, status, estimatedValue, finalValue, delta,
                currency, offerExpiry, channel, conditionAnswers, paymentRef, refundRef, agreementRef,
                relatedParty, type, note, gradingEvent);
    }

    /** The grading history, only when there is one (the map left the key off otherwise). */
    public TradeInValuationView withGrading(List<GradingEventView> history) {
        return new TradeInValuationView(id, href, imei, deviceRef, status, estimatedValue, finalValue, delta,
                currency, offerExpiry, channel, conditionAnswers, paymentRef, refundRef, agreementRef,
                relatedParty, type, note, history == null || history.isEmpty() ? null : history);
    }
}
