package com.bss.process.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * TMF701's lever: an operator completes, retries or fails a task by hand.
 * The state arrives as an open node so an absent key still reads as the
 * literal {@code "null"} the allowed-states check has always refused —
 * a typed {@code String} would have handed {@code List.of(...).contains}
 * a null and thrown where the door used to answer 400.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskPatchRequest(JsonNode state, JsonNode message) {

    @JsonIgnore
    public String stateText() {
        return Json.valueOf(state);
    }

    /** An absent message and an explicit null are one thing, as they were. */
    @JsonIgnore
    public String messageOrDefault() {
        return Json.absent(message) ? "operator decision" : Json.valueOf(message);
    }
}
