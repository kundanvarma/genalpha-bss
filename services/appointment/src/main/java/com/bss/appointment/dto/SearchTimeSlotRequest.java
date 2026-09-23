package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The posted TMF646 SearchTimeSlot. Its blocks are the caller's own documents
 * (a place, an order, a party, the windows they asked about) and travel through
 * the seam untouched — so they stay open nodes; what a record buys here is that
 * a body cannot carry a field this service never declared. The accessors keep
 * the map's shape test: anything of the wrong JSON kind was silently dropped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchTimeSlotRequest(JsonNode relatedPlace, JsonNode relatedEntity,
                                    JsonNode relatedParty, JsonNode requestedTimeSlot) {

    public static final SearchTimeSlotRequest EMPTY = new SearchTimeSlotRequest(null, null, null, null);

    public JsonNode place() {
        return Json.objectOrNull(relatedPlace);
    }

    public JsonNode entities() {
        return Json.arrayOrNull(relatedEntity);
    }

    public JsonNode party() {
        return Json.objectOrNull(relatedParty);
    }

    public JsonNode windows() {
        return Json.arrayOrNull(requestedTimeSlot);
    }
}
