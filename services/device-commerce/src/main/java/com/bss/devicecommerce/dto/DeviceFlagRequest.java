package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Blacklist an IMEI: why (lost by default) and on whose word (manual by default). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceFlagRequest(String imei, String reason, String sourceRef) {
}
