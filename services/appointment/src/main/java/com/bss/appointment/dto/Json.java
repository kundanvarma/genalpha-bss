package com.bss.appointment.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The map's leniency, written out. Every request in this service used to be a
 * {@code Map<String, Object>} and read with {@code containsKey} /
 * {@code String.valueOf(map.get(k))}; a JSON null and an absent key both came
 * back as Java null there, and {@code String.valueOf} minted the literal
 * {@code "null"}. A record's components tell absent (Java null) from an
 * explicit JSON null ({@code NullNode}) — these helpers re-join them exactly
 * where the old code could not tell them apart, so the wire does not move.
 */
public final class Json {

    private Json() {
    }

    /** {@code containsKey(k)} — the key was in the posted document at all. */
    public static boolean present(JsonNode node) {
        return node != null;
    }

    /** {@code String.valueOf(map.get(k))} — including the literal "null" the map minted. */
    public static String valueOf(JsonNode node) {
        return node == null || node.isNull() ? "null" : plain(node);
    }

    /** {@code map.get(k) == null ? null : String.valueOf(map.get(k))}. */
    public static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : plain(node);
    }

    /** The service's {@code join(value)}: a list becomes a trimmed CSV, anything else its own text. */
    public static String join(JsonNode node) {
        if (node != null && node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                    .map(Json::plain).map(String::trim).collect(Collectors.joining(","));
        }
        return valueOf(node);
    }

    /** An object node, or null — the old {@code instanceof Map} test. */
    public static JsonNode objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }

    /** An array node, or null — the old {@code instanceof List} test. */
    public static JsonNode arrayOrNull(JsonNode node) {
        return node != null && node.isArray() ? node : null;
    }

    private static String plain(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
