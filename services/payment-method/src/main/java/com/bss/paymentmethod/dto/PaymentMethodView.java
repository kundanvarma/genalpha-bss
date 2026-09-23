package com.bss.paymentmethod.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A saved method. {@code @type} is the method's kind (bankCard, bnplToken),
 * not a resource marker, and it keeps its third position on the wire.
 */
@JsonPropertyOrder({"id", "href", "@type", "status", "preferred", "details", "relatedParty"})
public record PaymentMethodView(
        String id,
        String href,
        @JsonProperty("@type") String type,
        String status,
        boolean preferred,
        CardDetails details,
        List<PartyRef> relatedParty) {
}
