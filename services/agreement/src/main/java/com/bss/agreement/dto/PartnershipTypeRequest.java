package com.bss.agreement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted partnership kind. The role list stays a tree because the service
 * sanitises it entry by entry — an entry without a name is a contradiction,
 * and the refusal must stay the service's own, not a parser's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnershipTypeRequest(
        String name,
        String description,
        String status,
        JsonNode roleType) {
}
