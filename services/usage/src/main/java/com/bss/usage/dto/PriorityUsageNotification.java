package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /internal/ocs/priorityUsage: GB that rode the priority slice, as the OCS counted it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriorityUsageNotification(String tenantId, String partyId, String serviceId, BigDecimal gb,
        BigDecimal upliftPerGb, String currency) {
}
