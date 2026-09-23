package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted promotion. The four blocks the map path read leniently stay trees:
 * the percentage keeps the caller's own scale through
 * {@code new BigDecimal(text)} (a {@code BigDecimal} field would re-scale a
 * posted 10.00 that the map had read as a double), and durationMonths is set
 * only by a real JSON number — a posted "3" was ignored and still is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromotionRequest(
        String name,
        String code,
        String description,
        String lifecycleStatus,
        JsonNode percentage,
        JsonNode durationMonths,
        JsonNode appliesTo,
        JsonNode validFor) {

    public static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }
}
