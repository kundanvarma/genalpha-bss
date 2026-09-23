package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * TMF642 Alarm. The standard's mandatory attributes ride EVERY row — a
 * house-raised alarm with no cause answers "unknown", one with no source
 * answers "network" — so nothing here is ever left off.
 */
@JsonPropertyOrder({"id", "href", "alarmedObject", "alarmType", "perceivedSeverity", "state",
        "probableCause", "sourceSystemId", "alarmRaisedTime", "@type"})
public record AlarmView(
        String id,
        String href,
        String alarmedObject,
        String alarmType,
        String perceivedSeverity,
        String state,
        String probableCause,
        String sourceSystemId,
        String alarmRaisedTime) {

    @JsonProperty("@type")
    public String atType() {
        return "Alarm";
    }
}
