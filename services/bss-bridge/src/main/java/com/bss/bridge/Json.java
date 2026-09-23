package com.bss.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The open edge's leniency, kept honest. A request body used to be a
 * {@code Map<String, Object>}; every read of it went through
 * {@code map.get(k)} and {@code String.valueOf(...)}, and both have
 * behaviour a typed field would quietly change:
 *
 * <ul>
 *   <li>an absent key and an explicit JSON {@code null} were the SAME thing
 *       ({@code get} returns Java null for both), and</li>
 *   <li>{@code String.valueOf} printed the Java rendering of whatever
 *       arrived — a number, a boolean, even a nested block — never a
 *       parse failure.</li>
 * </ul>
 *
 * These helpers reproduce exactly that over a {@link JsonNode}, so the
 * record keeps the map's contract instead of inventing a stricter one.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** True where {@code map.get(key)} would have returned Java null. */
    public static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    /** {@code String.valueOf(map.get(key))} — including the literal "null". */
    public static String valueOf(JsonNode node) {
        return String.valueOf(absent(node) ? null : MAPPER.convertValue(node, Object.class));
    }

    /** {@code map.get(key) == null ? null : String.valueOf(map.get(key))}. */
    public static String textOrNull(JsonNode node) {
        return absent(node) ? null : valueOf(node);
    }
}
