package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * DeviceChargeRequestedEvent payload — a trade-in graded BELOW its estimate;
 * the difference is billing's to collect once the customer accepts it.
 */
@JsonPropertyOrder({"tradeInValuationId", "amount", "currency", "relatedParty", "@type"})
public record DeviceChargeRequest(String tradeInValuationId, BigDecimal amount, String currency,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) {

    public static DeviceChargeRequest of(String tradeInValuationId, BigDecimal amount, String currency,
            String partyId) {
        return new DeviceChargeRequest(tradeInValuationId, amount, currency,
                partyId == null ? null : List.of(RelatedPartyRef.customer(partyId)), "DeviceChargeRequest");
    }
}
