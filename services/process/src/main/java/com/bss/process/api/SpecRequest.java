package com.bss.process.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Design intent, posted. Every component is an open node because the map
 * this replaces read each one through {@code String.valueOf} — a posted
 * number became its text and a posted block became its Java rendering,
 * and none of it was ever a parse failure. The record's job here is that
 * a body cannot carry a field the service never declared (no tenant, no
 * id, no clock), not that every leaf is typed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpecRequest(JsonNode code, JsonNode name, JsonNode description,
        JsonNode taskFlowSpecification) {

    @JsonIgnore
    public String codeText() {
        return Json.textOrNull(code);
    }

    @JsonIgnore
    public String nameText() {
        return Json.textOrNull(name);
    }

    @JsonIgnore
    public String descriptionText() {
        return Json.textOrNull(description);
    }

    /** The authored task list, only when it really is a list — as before. */
    @JsonIgnore
    public JsonNode taskListOrNull() {
        return taskFlowSpecification != null && taskFlowSpecification.isArray()
                ? taskFlowSpecification : null;
    }
}
