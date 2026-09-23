package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Where a gated offering may be sold. The productOffering block is the
 * caller's own reference, stored verbatim and answered verbatim — an open map
 * behind the typed envelope.
 */
@JsonPropertyOrder({"id", "href", "name", "productOffering", "postcodePrefix",
        "lastUpdate", "@type"})
public record ServiceableAreaView(
        String id,
        String href,
        @JsonInclude(JsonInclude.Include.NON_NULL) String name,
        Map<String, Object> productOffering,
        String postcodePrefix,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static ServiceableAreaView of(String id, String href, String name,
            Map<String, Object> productOffering, String postcodePrefix, OffsetDateTime lastUpdate) {
        return new ServiceableAreaView(id, href, name, productOffering, postcodePrefix,
                lastUpdate, "ServiceableArea");
    }
}
