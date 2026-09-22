package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The financed device on an agreement: catalog ref plus the identifiers we know. */
@JsonPropertyOrder({"id", "imei", "serialNumber", "@referredType"})
public record DeviceRef(String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) String imei,
        @JsonInclude(JsonInclude.Include.NON_NULL) String serialNumber,
        @JsonProperty("@referredType") String referredType) {

    public static DeviceRef logicalResource(String id, String imei, String serialNumber) {
        return new DeviceRef(id, imei, serialNumber, "LogicalResource");
    }
}
