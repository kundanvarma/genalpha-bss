package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /imsiRange. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImsiRangeRequest(String hostPartyId, String hostName, String prefix, String fromImsi, String toImsi,
        Integer capacity, String note) {
}
