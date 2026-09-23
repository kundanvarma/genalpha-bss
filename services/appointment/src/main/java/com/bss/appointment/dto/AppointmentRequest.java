package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A booking as the caller posts it. The window is read as text and parsed here
 * (an unparseable instant is the 500 it has always been); the place and the
 * entities being installed are the caller's documents, stored verbatim in their
 * JSON columns and handed to the field-service seam unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppointmentRequest(JsonNode validFor, JsonNode description,
                                 JsonNode place, JsonNode relatedPlace, JsonNode relatedEntity) {

    public static final AppointmentRequest EMPTY = new AppointmentRequest(null, null, null, null, null);

    /** TMF646 validFor, when the caller sent one as an object — anything else was never read. */
    public JsonNode window() {
        return Json.objectOrNull(validFor);
    }

    public String descriptionText() {
        return Json.textOrNull(description);
    }

    /** {@code place}, or {@code relatedPlace} when the shop posted it under the TMF646 name. */
    public JsonNode placeDocument() {
        JsonNode own = value(place);
        return own != null ? own : value(relatedPlace);
    }

    public JsonNode placeObject() {
        return Json.objectOrNull(placeDocument());
    }

    public JsonNode entityDocument() {
        return value(relatedEntity);
    }

    /** A map's {@code get} answered Java null for a missing key and for an explicit JSON null alike. */
    private static JsonNode value(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }
}
