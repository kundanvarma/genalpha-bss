package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A TMF921-shaped network intent drafted from a sales ask in plain language. */
@JsonPropertyOrder({"name", "expression", "provider", "model"})
public record IntentDraft(String name, IntentExpression expression, String provider, String model) {

    @JsonPropertyOrder({"place", "latencyMs", "bandwidthMbps", "aiTokensMillions"})
    public record IntentExpression(String place, long latencyMs, long bandwidthMbps,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long aiTokensMillions) {
    }
}
