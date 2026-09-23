package com.bss.paymentmethod.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Whose card this is. Built from a two-entry {@code Map.of} before, whose
 * iteration order Java re-salts per JVM — declaring it pins the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role"})
public record PartyRef(String id, String role) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, "customer");
    }
}
