package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One data counter on an OCS subscriber, in GB as the OCS counts them. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "ratePlanId", "totalGB", "usedGB", "rolloverGB", "rollover"})
public record OcsBucket(String id, String name, String ratePlanId, double totalGB, double usedGB, double rolloverGB,
        boolean rollover) {
}
