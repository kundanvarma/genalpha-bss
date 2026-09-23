package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/** The open-access shortlist for one address and one technology. */
@JsonPropertyOrder({"place", "technology", "accessOption", "@type"})
public record AccessOptionsResult(
        Map<String, Object> place,
        String technology,
        List<AccessOption> accessOption,
        @JsonProperty("@type") String type) {

    public static AccessOptionsResult of(Map<String, Object> place, String technology,
            List<AccessOption> options) {
        return new AccessOptionsResult(place, technology, options, "QueryAccessOptions");
    }
}
