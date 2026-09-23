package com.bss.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One rung of the rail: where it sits, and what it points at. */
@JsonPropertyOrder({"priority", "offering"})
public record RecommendationItem(int priority, OfferingRef offering) {
}
