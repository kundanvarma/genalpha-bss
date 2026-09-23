package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One binding on the operator's carrier menu. The API key is a secret-REF, never
 * the value — the record has no field for it, which is the point. {@code methods}
 * is the stored JSON text, answered as the string the map answered.
 */
@JsonPropertyOrder({"carrier", "displayName", "baseUrl", "secretRef", "methods",
        "postcodePrefix", "isDefault", "enabled", "@type"})
public record CarrierConfigView(
        String carrier,
        String displayName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String baseUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String secretRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String methods,
        @JsonInclude(JsonInclude.Include.NON_NULL) String postcodePrefix,
        // "isDefault" is the wire key; without this a getter-shaped accessor
        // would offer Jackson the property "default" as well
        @JsonProperty("isDefault") boolean isDefault,
        boolean enabled) {

    @JsonProperty("@type")
    public String atType() {
        return "CarrierConfig";
    }
}
