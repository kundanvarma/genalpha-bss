package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** An IMSI range the host lent this MVNO. */
@JsonPropertyOrder({"id", "hostPartyId", "hostName", "prefix", "fromImsi", "toImsi", "capacity", "note", "@type"})
public record ImsiRangeView(String id, String hostPartyId, String hostName, String prefix, String fromImsi,
        String toImsi, Integer capacity, String note, @JsonProperty("@type") String type) {
}
