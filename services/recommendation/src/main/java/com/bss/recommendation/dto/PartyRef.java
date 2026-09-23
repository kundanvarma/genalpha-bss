package com.bss.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Who the rail is for. Pinned: the map before it was a two-entry Map.of. */
@JsonPropertyOrder({"id", "role"})
public record PartyRef(String id, String role) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, "customer");
    }
}
