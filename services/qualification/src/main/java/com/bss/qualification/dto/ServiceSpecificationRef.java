package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The specification a qualified service realises, named not linked. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "@referredType"})
public record ServiceSpecificationRef(
        String name,
        @JsonProperty("@referredType") String referredType) {

    public static ServiceSpecificationRef of(String name) {
        return new ServiceSpecificationRef(name, "ServiceSpecification");
    }
}
