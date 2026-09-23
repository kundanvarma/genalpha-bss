package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A TMF reference to something that lives elsewhere (an offering, an order).
 * Whatever else the caller put beside the id round-trips in {@code extensions}
 * — this body is echoed back on the reserve task and rides the domain event.
 *
 * <p>Built through a DELEGATING creator so the posted key order survives: a
 * creator-bound {@code @JsonAnySetter} hands the unknown keys back last-first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name"})
public record EntityRef(
        @JsonProperty("id") String id,
        @JsonProperty("href") String href,
        @JsonProperty("name") String name,
        @JsonAnyGetter Map<String, Object> extensions) {

    public EntityRef {
        extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static EntityRef fromMap(Map<String, Object> posted) {
        if (posted == null) {
            return null;
        }
        Map<String, Object> rest = new LinkedHashMap<>(posted);
        Object id = rest.remove("id");
        Object href = rest.remove("href");
        Object name = rest.remove("name");
        return new EntityRef(str(id), str(href), str(name), rest);
    }

    static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
