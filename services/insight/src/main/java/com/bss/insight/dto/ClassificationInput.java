package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** The battery's write-back. Evidence is {field: verbatim quote}; evidenceSpace=twin says the quotes are in twin space. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassificationInput(String sentiment, String aspect, String category, String painPoint, Integer painImpact,
        String loyaltyIndicator, Boolean churnSignal, String churnReason, JsonNode evidence, String evidenceSpace,
        String provider, String model) {
}
