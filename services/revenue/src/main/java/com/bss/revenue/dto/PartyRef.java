package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Who a journal entry belongs to. */
@JsonPropertyOrder({"id", "role"})
public record PartyRef(@JsonProperty("id") String id, @JsonProperty("role") String role) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, "customer");
    }
}
