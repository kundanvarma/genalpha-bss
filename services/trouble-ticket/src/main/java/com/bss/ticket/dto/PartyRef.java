package com.bss.ticket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The customer a ticket belongs to, as TMF621 writes it: one related party in
 * the role of customer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role", "@referredType"})
public record PartyRef(
        String id,
        String role,
        @JsonProperty("@referredType") String referredType) {

    public static PartyRef customer(String partyId) {
        return new PartyRef(partyId, "customer", "Individual");
    }
}
