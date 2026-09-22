package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** The one-click fix a suggestion carries: save a preset here, or an HTTP call the desk runs with the user's own token. */
public sealed interface SuggestedAction permits SuggestedAction.Preset, SuggestedAction.Http {

    String kind();

    @JsonPropertyOrder({"kind", "desk", "form", "values"})
    record Preset(String kind, String desk, String form, Map<String, String> values) implements SuggestedAction {
        public static Preset of(String desk, String form, Map<String, String> values) {
            return new Preset("preset", desk, form, values);
        }
    }

    /** kind=action: {method, path, body} — insight holds no cross-service credential; the desk executes it. */
    @JsonPropertyOrder({"kind", "method", "path", "body"})
    record Http(String kind, String method, String path, JsonNode body) implements SuggestedAction {
        public static Http of(String method, String path, JsonNode body) {
            return new Http("action", method, path, body);
        }
    }
}
