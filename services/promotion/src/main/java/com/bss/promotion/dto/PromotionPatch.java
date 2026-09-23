package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A promotion patch: two fields, and an explicit null means "leave alone",
 * as it did when a missing map key and a null one were the same thing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromotionPatch(String lifecycleStatus, JsonNode percentage) {
}
