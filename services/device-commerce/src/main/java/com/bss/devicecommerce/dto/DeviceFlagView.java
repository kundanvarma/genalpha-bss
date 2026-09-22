package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A flag on an IMEI (blacklisted: lost/stolen) — the row the trade-in quote reads and the EIR seam publishes. */
@JsonPropertyOrder({"id", "imei", "flag", "reason", "sourceRef", "createdAt", "@type"})
public record DeviceFlagView(String id, String imei, String flag,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceRef,
        String createdAt, @JsonProperty("@type") String type) {

    public static final String TYPE = "DeviceFlag";
}
