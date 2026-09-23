package com.bss.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The offering a recommendation points at, named not embedded. */
@JsonPropertyOrder({"id", "name", "@referredType"})
public record OfferingRef(
        String id,
        String name,
        @JsonProperty("@referredType") String referredType) {

    public static OfferingRef of(Object id, Object name) {
        return new OfferingRef(id == null ? null : String.valueOf(id),
                name == null ? null : String.valueOf(name), "ProductOffering");
    }
}
