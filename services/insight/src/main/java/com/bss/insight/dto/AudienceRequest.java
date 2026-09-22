package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Create or patch a saved audience; the criteria tree is the marketer's document. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AudienceRequest(String name, JsonNode criteria, String population) {

    public boolean hasCriteria() {
        return criteria != null && !criteria.isNull();
    }
}
