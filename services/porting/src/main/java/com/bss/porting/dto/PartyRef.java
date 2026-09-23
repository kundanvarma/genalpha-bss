package com.bss.porting.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Whose number this is. The map path built this with a two-entry
 * {@code Map.of}, whose iteration order Java re-salts on every start — so
 * the wire has been showing {@code id, role} and {@code role, id} at random.
 * Declaring the order is the one key-order change this component owns.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role"})
public record PartyRef(String id, String role) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, "customer");
    }
}
