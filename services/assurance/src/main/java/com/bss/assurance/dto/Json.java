package com.bss.assurance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The map's leniency, written out. Every body in this service used to be a
 * {@code Map<String, Object>} read with {@code String.valueOf(map.get(k))} —
 * which minted the literal {@code "null"} for a missing key, {@code "7"} for a
 * number, and {@code "{a=b}"} / {@code "[a, b]"} for a nested block, because
 * that is what Java's own {@code toString} writes. A record's components tell
 * absent from an explicit JSON null; these helpers re-join them, and
 * {@link #valueOf} converts the node back to plain Java first so the odd
 * payloads keep the exact strings the wire already has.
 */
public final class Json {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private Json() {
    }

    /** {@code containsKey(k)} — the key was in the posted document at all. */
    public static boolean present(JsonNode node) {
        return node != null;
    }

    /** {@code map.get(k) != null} — a missing key and an explicit JSON null were one. */
    public static boolean set(JsonNode node) {
        return node != null && !node.isNull();
    }

    /** {@code String.valueOf(map.get(k))}, including the literal "null" and Java's own map/list text. */
    public static String valueOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return String.valueOf(PLAIN.convertValue(node, Object.class));
    }

    /** {@code map.get(k) == null ? null : String.valueOf(map.get(k))}. */
    public static String textOrNull(JsonNode node) {
        return set(node) ? valueOf(node) : null;
    }

    /** An object node, or null — the old {@code instanceof Map} test. */
    public static JsonNode objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }
}
