package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF RelatedParty: who the bill, case or note belongs to. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "role", "@referredType"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record RelatedPartyRef(String id, String role, @JsonProperty("@referredType") String referredType) {

    public static RelatedPartyRef customer(String id) {
        return new RelatedPartyRef(id, "customer", null);
    }

    public static RelatedPartyRef individual(String id) {
        return new RelatedPartyRef(id, "customer", "Individual");
    }
}
