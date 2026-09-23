package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A posted serviceable area. The offering reference stays a tree: the service
 * refuses anything without an id with its own message, exactly as the map path
 * did, instead of letting a parser refuse the shape first.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceableAreaRequest(
        String postcodePrefix,
        JsonNode productOffering,
        String name) {
}
