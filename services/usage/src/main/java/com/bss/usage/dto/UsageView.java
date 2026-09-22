package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TMF635 Usage: the record as stored (the posted document, verbatim) with
 * the fields usage derived laid over it. What the mediation feed posted
 * beyond the known keys rides in {@code extensions}; the characteristic and
 * the specification stay open blocks. {@code zoneEntered} rides only on the
 * ingest answer that announced a new zone.
 */
@JsonPropertyOrder({"id", "href", "usageType", "usageDate", "usageCharacteristic", "relatedParty", "status",
        "zone", "pooledValue", "usageSpecification", "@type", "zoneEntered"})
public record UsageView(String id, String href,
        @JsonInclude(JsonInclude.Include.NON_NULL) String usageType,
        OffsetDateTime usageDate,
        Object usageCharacteristic,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object relatedParty,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String zone,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal pooledValue,
        JsonNode usageSpecification,
        @JsonProperty("@type") String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean zoneEntered,
        @JsonAnyGetter Map<String, Object> extensions) {

    public UsageView {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    /** The ingest answer that also says "this party just landed in a new zone". */
    public UsageView withZoneEntered() {
        return new UsageView(id, href, usageType, usageDate, usageCharacteristic, relatedParty, status,
                zone, pooledValue, usageSpecification, type, true, extensions);
    }
}
