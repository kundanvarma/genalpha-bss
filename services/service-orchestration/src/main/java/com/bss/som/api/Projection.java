package com.bss.som.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * TMF630 attribute selection over a rendered view: the asked-for top-level
 * keys, the always-along keys first. Records and stored documents alike
 * are trees here, so one mechanic serves both halves of every face.
 */
public final class Projection {

    private Projection() {
    }

    /** {@code fields} as the caller wrote it; a dotted path keeps its top-level key. */
    public static JsonNode select(ObjectMapper mapper, Object view, String fields, String... always) {
        JsonNode full = view instanceof JsonNode n ? n : mapper.valueToTree(view);
        if (fields == null || fields.isBlank()) {
            return full;
        }
        List<String> keep = new ArrayList<>(List.of(always));
        for (String f : fields.split(",")) {
            keep.add(f.split("\\.")[0].trim());
        }
        ObjectNode slim = mapper.createObjectNode();
        for (String k : keep) {
            if (full.has(k)) {
                slim.set(k, full.get(k));
            }
        }
        return slim;
    }

    /** The same, keeping the field names whole (no dotted-path split). */
    public static JsonNode selectExact(ObjectMapper mapper, Object view, String fields, String... always) {
        JsonNode full = view instanceof JsonNode n ? n : mapper.valueToTree(view);
        if (fields == null || fields.isBlank()) {
            return full;
        }
        List<String> keep = new ArrayList<>(List.of(always));
        for (String f : fields.split(",")) {
            keep.add(f.trim());
        }
        ObjectNode slim = mapper.createObjectNode();
        for (String k : keep) {
            if (full.has(k)) {
                slim.set(k, full.get(k));
            }
        }
        return slim;
    }
}
