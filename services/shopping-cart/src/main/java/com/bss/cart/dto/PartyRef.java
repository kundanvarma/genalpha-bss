package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The party a cart or an order belongs to: TMF relatedParty, one row. */
@JsonPropertyOrder({"id", "role", "@referredType"})
public record PartyRef(String id, String role, @JsonProperty("@referredType") String referredType) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, "customer", "Individual");
    }
}
