package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The customer a visit belongs to. The key order is the one the wire already
 * has: the old {@code Map.of("id", …, "role", …, "@referredType", …)} printed
 * {@code role, @referredType, id}, and a re-salted map is not a contract change
 * to make on purpose.
 */
@JsonPropertyOrder({"role", "@referredType", "id"})
public record PartyRef(String role, @JsonProperty("@referredType") String referredType, String id) {

    public static PartyRef customer(String id) {
        return new PartyRef("customer", "Individual", id);
    }
}
