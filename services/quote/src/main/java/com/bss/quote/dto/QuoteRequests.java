package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** Request bodies on the TMF648 quote face — records, so a body cannot carry a field it does not declare. */
public final class QuoteRequests {

    private QuoteRequests() {
    }

    /** POST /quote — a quote is born from a feasibility-checked intent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuoteRequest(String intentId, String description) {
    }

    /** PATCH /quote/{id} — a discount, or the approved/rejected decision. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuotePatch(BigDecimal discountPercent, String state) {
    }

    /** POST /quote/configRule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigRuleRequest(String ruleType, String subjectOffering, String objectOffering, Integer qty,
            String message) {
    }

    /** POST /quote/validate — the line items to check against the rules. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidateRequest(List<LineItem> items) {

        public ValidateRequest {
            items = items == null ? List.of() : items;
        }
    }

    /** POST /quote/guidedQuestion. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuidedQuestionRequest(String questionKey, String prompt, Integer sortOrder) {
    }

    /** POST /quote/guidedRecommendation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuidedRecommendationRequest(String questionKey, String answerValue, String offeringName,
            Integer quantity) {
    }

    /** POST /quote/pricingRule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingRuleRequest(String offeringName, Integer minQuantity, String segment,
            BigDecimal discountPercent) {
    }

    /** POST /quote/{id}/sign — the e-sign callback. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignRequest(String signedBy) {
    }
}
