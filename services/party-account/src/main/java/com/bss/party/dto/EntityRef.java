package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A TMF entity reference as this component writes it: the id and what it refers to. */
@JsonPropertyOrder({"id", "@referredType"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityRef(
        String id,
        @JsonProperty("@referredType") @JsonInclude(JsonInclude.Include.NON_NULL) String referredType) {

    public static EntityRef organization(String id) {
        return new EntityRef(id, "Organization");
    }

    public static EntityRef individual(String id) {
        return new EntityRef(id, "Individual");
    }
}
