package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** {status: recorded, partyId, deviceModel}. */
@JsonPropertyOrder({"status", "partyId", "deviceModel"})
public record DeviceDetectionReceipt(String status, String partyId, String deviceModel) {
}
