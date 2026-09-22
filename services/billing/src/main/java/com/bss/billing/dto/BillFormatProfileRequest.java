package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Create or patch a format profile. {@code customizationId} and
 * {@code profileId} may be cleared with an explicit null, so they arrive as
 * trees: an absent key is a Java null (leave the row alone), a JSON null is
 * a NullNode (clear it). Optional cannot tell the two apart on a creator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillFormatProfileRequest(String code, String name, String syntax, JsonNode customizationId,
        JsonNode profileId, Boolean paymentReference) {

    /** The key was in the body, null or not. */
    public static boolean given(JsonNode n) {
        return n != null && !n.isMissingNode();
    }

    /** The value to store: null clears, a text sets, anything else reads as its text. */
    public static String textOf(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }
}
