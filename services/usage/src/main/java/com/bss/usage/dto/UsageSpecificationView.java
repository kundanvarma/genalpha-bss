package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** TMF635 UsageSpecification: the stored document with the server's keys laid over it. */
@JsonPropertyOrder({"id", "href", "name", "lastUpdate", "@type"})
public record UsageSpecificationView(String id, String href, String name, OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type, @JsonAnyGetter Map<String, Object> extensions) {

    public UsageSpecificationView {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }
}
