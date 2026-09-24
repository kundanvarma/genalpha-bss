package com.bss.flow;

import java.util.Map;

/**
 * Reading identifiers off TM Forum wire maps without minting the text
 * {@code "null"}: {@code String.valueOf} of a map's {@code get("id")} turns a missing id into
 * a four-letter id that then serves, owns, and gets charged. Every reader here
 * reads the value once and gets {@code null} back when there is none, so the
 * caller decides what a missing id means instead of a downstream system.
 */
public final class Wire {

    private Wire() {
    }

    /** The {@code id} of a wire map (or of anything that is not a map) as text, or null. */
    public static String idOf(Object mapOrNull) {
        return textOf(mapOrNull, "id");
    }

    /** The named field of a wire map as text, or null when the map or the field is absent. */
    public static String textOf(Object mapOrNull, String field) {
        Object value = mapOrNull instanceof Map<?, ?> m ? m.get(field) : null;
        return value == null ? null : value.toString();
    }
}
