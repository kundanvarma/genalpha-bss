package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF related party: who a message is for. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "role", "@referredType"})
public record PartyRef(
        @JsonProperty("id") String id,
        @JsonProperty("role") String role,
        @JsonProperty("@referredType") String referredType) {

    public static PartyRef customer(String id) {
        return new PartyRef(id, "customer", "Individual");
    }
}
