package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reference to another resource: the offering on a quote line, the intent
 * a quote was born from, the order and agreement an accepted quote became,
 * the lead behind an opportunity and the opportunity behind a lead. Fields a
 * caller adds beside {id, href, name} round-trip through {@code extensions}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name"})
public record EntityRef(String id, String href, String name,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public EntityRef {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    /** {id} — the bare reference. */
    public static EntityRef of(String id) {
        return new EntityRef(id, null, null, null);
    }

    /** {id, name} — an offering on a quote line. */
    public static EntityRef of(String id, String name) {
        return new EntityRef(id, null, name, null);
    }

    /** {id, href} — a resource this component or a sibling serves. */
    public static EntityRef at(String id, String href) {
        return new EntityRef(id, href, null, null);
    }
}
