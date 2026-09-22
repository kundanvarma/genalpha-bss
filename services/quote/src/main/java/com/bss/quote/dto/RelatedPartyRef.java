package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The party a quote, order or agreement is for, with its role. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "role"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record RelatedPartyRef(String id, String role) {

    public static RelatedPartyRef customer(String id) {
        return new RelatedPartyRef(id, "customer");
    }
}
