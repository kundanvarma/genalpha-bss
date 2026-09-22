package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A TMF entity reference — ProductSpecificationRef, ServiceSpecificationRef,
 * ProductOfferingRef, ProductOfferingPriceRef, ChannelRef: {id, href, name,
 * version, @referredType}. House or schema fields a client adds (@type,
 * @baseType, @schemaLocation, an embedded price on a federated ref) round-trip
 * through {@code extensions} untouched.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "version", "@referredType"})
public record EntityRef(String id, String href, String name, String version,
        @JsonProperty("@referredType") String referredType,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extensions) {

    public EntityRef {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    /** {id, name} — the minimal ref a house payload carries. */
    public static EntityRef of(String id, String name) {
        return new EntityRef(id, null, name, null, null, null);
    }

    /** {id, name, @referredType} — a standard ref to a known resource. */
    public static EntityRef of(String id, String name, String referredType) {
        return new EntityRef(id, null, name, null, referredType, null);
    }

    /** {id, @referredType}. */
    public static EntityRef to(String id, String referredType) {
        return new EntityRef(id, null, null, null, referredType, null);
    }

    /** An extension field a client put beside the standard ones, or null. */
    public Object extension(String key) {
        return extensions.get(key);
    }
}
