package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** EMS-grade attribute updates: cause, severity, type, state. An explicit null leaves the column alone. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmPatch(JsonNode probableCause, JsonNode perceivedSeverity,
                         JsonNode alarmType, JsonNode state) {

    public static final AlarmPatch EMPTY = new AlarmPatch(null, null, null, null);
}
