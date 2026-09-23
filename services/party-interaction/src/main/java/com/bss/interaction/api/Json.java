package com.bss.interaction.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The open edge's two habits, written down once.
 *
 * <p>A TMF683 body arrives as a tree and the service reads it leniently — the
 * same leniency a {@code Map<String, Object>} gave it. {@link #present} is the
 * map's {@code get(k) != null}: an absent key and an explicit JSON null are
 * one thing. {@link #valueOfLike} is the map's {@code String.valueOf(get(k))},
 * including the parts nobody would write on purpose: the literal {@code "null"}
 * for a missing value, and Java's own rendering ({@code {a=1}}, {@code [a, b]})
 * for a nested block. Both are contract, not accident — see
 * docs/engineering-conventions.md §1.
 */
public final class Json {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private Json() {
    }

    /** The map's {@code get(k) != null}: absent and explicit-null are one. */
    public static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    /** Exactly what {@code String.valueOf(map.get(k))} used to print. */
    public static String valueOfLike(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return String.valueOf(PLAIN.convertValue(node, Object.class));
    }
}
