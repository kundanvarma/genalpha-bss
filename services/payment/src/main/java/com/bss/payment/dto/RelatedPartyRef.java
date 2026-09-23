package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF related party: who the payment belongs to. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role", "@referredType"})
public record RelatedPartyRef(
        @JsonProperty("id") String id,
        @JsonProperty("role") String role,
        @JsonProperty("@referredType") String referredType) {

    public static RelatedPartyRef payer(String id) {
        return new RelatedPartyRef(id, "payer", "Individual");
    }

    public static RelatedPartyRef customer(String id) {
        return new RelatedPartyRef(id, "customer", null);
    }
}
