package com.bss.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * TMF680: what this customer should see next, in priority order. The
 * channels render the order; nothing here explains it — the ranker seam
 * decides, and the API does not change when the ranker does.
 */
@JsonPropertyOrder({"id", "name", "relatedParty", "recommendationItem", "@type"})
public record RecommendationView(
        String id,
        String name,
        List<PartyRef> relatedParty,
        List<RecommendationItem> recommendationItem,
        @JsonProperty("@type") String type) {

    public static RecommendationView of(String id, String party, List<RecommendationItem> items) {
        return new RecommendationView(id, "Recommended for you",
                List.of(PartyRef.customer(party)), items, "Recommendation");
    }
}
