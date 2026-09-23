package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Who the parcel or the visit belongs to. The key order is the one the wire
 * already has — the old two-entry {@code Map.of("id", …, "role", "customer")}
 * printed {@code role, id}, and even a two-entry map is re-salted per JVM.
 */
@JsonPropertyOrder({"role", "id"})
public record PartyRef(String role, String id) {

    public static PartyRef customer(String id) {
        return new PartyRef("customer", id);
    }
}
