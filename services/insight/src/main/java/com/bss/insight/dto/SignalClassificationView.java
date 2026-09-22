package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** The battery's verdict on a signal; evidence is the model's own quotes, verified verbatim at the store. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"sentiment", "aspect", "category", "painPoint", "painImpact", "loyaltyIndicator", "churnSignal",
        "churnReason", "evidence", "provider", "model", "classifiedAt", "@type"})
public record SignalClassificationView(String sentiment, String aspect, String category, String painPoint, Integer painImpact,
        String loyaltyIndicator, boolean churnSignal, String churnReason, JsonNode evidence, String provider, String model,
        OffsetDateTime classifiedAt, @JsonProperty("@type") String type) {

    /** The fine-tune label: the taxonomy fields and the evidence, nothing about who or when. */
    public Label label(JsonNode evidence) {
        return new Label(sentiment, aspect, category, painPoint, painImpact, loyaltyIndicator, churnSignal, churnReason, evidence);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"sentiment", "aspect", "category", "painPoint", "painImpact", "loyaltyIndicator", "churnSignal",
            "churnReason", "evidence"})
    public record Label(String sentiment, String aspect, String category, String painPoint, Integer painImpact,
            String loyaltyIndicator, boolean churnSignal, String churnReason, JsonNode evidence) {
    }
}
