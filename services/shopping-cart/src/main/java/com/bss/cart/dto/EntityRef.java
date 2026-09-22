package com.bss.cart.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A TMF reference to something else — an offering on a cart line, the order a
 * checked-out cart became, the payment an order carries. Keys the caller
 * added beyond the standard's ride in {@code extensions} and round-trip in
 * the order they were posted (a creator-bound any-setter would reverse them,
 * so the record is built from the posted map instead).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "@referredType"})
public record EntityRef(String id, String href, String name,
        @JsonProperty("@referredType") String referredType,
        @JsonAnyGetter Map<String, Object> extensions) {

    public EntityRef {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EntityRef fromMap(Map<String, Object> posted) {
        Map<String, Object> rest = new LinkedHashMap<>(posted);
        return new EntityRef(text(rest.remove("id")), text(rest.remove("href")), text(rest.remove("name")),
                text(rest.remove("@referredType")), rest);
    }

    public static EntityRef of(String id, String name) {
        return new EntityRef(id, null, name, null, null);
    }

    public static EntityRef of(String id, String name, String referredType) {
        return new EntityRef(id, null, name, referredType, null);
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
