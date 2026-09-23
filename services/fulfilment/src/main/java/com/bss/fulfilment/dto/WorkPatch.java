package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** What the installer may change on a visit. Same reading as the parcel's patch. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkPatch(JsonNode state, JsonNode note) {

    public static final WorkPatch EMPTY = new WorkPatch(null, null);

    public String stateValue() {
        return Json.valueOf(state);
    }

    public String noteValue() {
        return Json.textOrNull(note);
    }
}
