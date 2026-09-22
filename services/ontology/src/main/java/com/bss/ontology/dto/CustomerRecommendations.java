package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * "What should I do next for this customer?" — the situation (and its
 * summary), how bad it is, the ranked recommendations, the last receipts, the
 * edges that did not answer, and the top recommendation said in one sentence.
 */
@JsonPropertyOrder({"customerId", "situation", "summary", "severity", "healthy", "recommendations", "receipts", "unanswered", "said", "@type"})
public record CustomerRecommendations(String customerId, List<Situation> situation, List<SituationSummary> summary, String severity,
        boolean healthy, List<Recommendation> recommendations, List<CustomerContext.Receipt> receipts, List<String> unanswered,
        String said, @JsonProperty("@type") String type) {
}
