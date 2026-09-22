package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /deviceDetection: the EIR's word on which handset a SIM is in. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceDetectionRequest(String partyId, String deviceModel, String tac, String imei) {
}
