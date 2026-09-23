package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** The one supported transition. Only a JSON string ever matched "resolved". */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceProblemPatch(JsonNode status) {

    public boolean resolving() {
        return status != null && status.isTextual() && "resolved".equals(status.asText());
    }
}
