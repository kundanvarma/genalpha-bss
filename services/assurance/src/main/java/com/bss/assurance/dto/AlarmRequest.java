package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * An alarm as the network raises it. The alarmed object is a TMF642 entity
 * reference when it carries an id and, when it does not, whatever the caller
 * sent printed the way the map printed it — that leniency is the contract, not
 * an accident to tidy up while typing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmRequest(JsonNode alarmedObject, JsonNode alarmType, JsonNode perceivedSeverity,
                           JsonNode probableCause, JsonNode sourceSystemId) {

    public static final AlarmRequest EMPTY = new AlarmRequest(null, null, null, null, null);

    /** Both keys are required; a missing key and an explicit JSON null were one to the map. */
    public boolean complete() {
        return Json.set(alarmedObject) && Json.set(perceivedSeverity);
    }

    public String alarmedObjectId() {
        JsonNode ref = Json.objectOrNull(alarmedObject);
        return ref != null && ref.hasNonNull("id")
                ? Json.valueOf(ref.get("id")) : Json.valueOf(alarmedObject);
    }

    public String alarmTypeOr(String fallback) {
        return Json.set(alarmType) ? Json.valueOf(alarmType) : fallback;
    }

    public String severity() {
        return Json.valueOf(perceivedSeverity);
    }

    public String probableCauseOrNull() {
        return Json.textOrNull(probableCause);
    }

    public String sourceSystemIdOr(String fallback) {
        return Json.set(sourceSystemId) ? Json.valueOf(sourceSystemId) : fallback;
    }
}
