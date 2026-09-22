package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF RelatedParty as usage writes it: the customer (or pool owner / member) a resource belongs to. */
@JsonPropertyOrder({"id", "role"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record RelatedPartyRef(String id, String role) {

    public static RelatedPartyRef customer(String id) {
        return new RelatedPartyRef(id, "customer");
    }
}
