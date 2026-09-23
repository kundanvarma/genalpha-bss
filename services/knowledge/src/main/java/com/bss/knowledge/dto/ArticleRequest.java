package com.bss.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST/PATCH article. The body is a record so it cannot carry a field the
 * library never declared — the tenant, the href and the clocks are the
 * service's, never the caller's. The components stay open nodes because the
 * door has always read them leniently: a number is accepted as a title and
 * becomes its own text, and an absent key and an explicit null both mean
 * "leave this alone".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleRequest(
        JsonNode id,
        JsonNode title,
        JsonNode body,
        JsonNode tags,
        JsonNode category,
        JsonNode audience,
        JsonNode status) {
}
