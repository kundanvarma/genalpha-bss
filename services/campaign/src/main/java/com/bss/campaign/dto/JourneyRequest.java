package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the desk (or the growth copilot's draft) sends to create or edit a
 * journey. {@code steps} and {@code arms} are the author's documents — a JSON
 * array, or the same array as a string — validated by the engine, never
 * re-shaped. The four trigger/segment/conversion fields are {@code JsonNode}
 * because an edit distinguishes "not mentioned" (left alone) from an explicit
 * {@code null} (cleared).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JourneyRequest(
        String name,
        JsonNode steps,
        String status,
        JsonNode triggerEventType,
        JsonNode triggerState,
        JsonNode segmentName,
        JsonNode conversionEvent,
        Integer holdoutPercent,
        String category,
        JsonNode arms,
        JsonNode messageVariants,
        Boolean autoTune,
        Integer priority) {

    /** The arms document, under either of its two names. */
    public JsonNode armsDocument() {
        return arms != null ? arms : messageVariants;
    }

    public boolean mentionsArms() {
        return arms != null || messageVariants != null;
    }

    /** A scalar's text; a JSON null (or an absent key) is null. */
    public static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
