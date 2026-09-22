package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * The OCS's "running low" line as it reaches the BSS (POST
 * /internal/ocs/usageThreshold, or translated from a SigScale balance
 * event): the subscriber, the bucket, what is left. The BSS never computes
 * the breach — it relays it and lets auto top-up act on it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"tenantId", "partyId", "serviceId", "ratePlanId", "bucketName", "totalGB", "usedGB",
        "remainingGB", "percentUsed", "threshold", "units", "source", "windowId"})
public record UsageThresholdNotification(String tenantId, String partyId, String serviceId, String ratePlanId,
        String bucketName, BigDecimal totalGB, BigDecimal usedGB, BigDecimal remainingGB, BigDecimal percentUsed,
        BigDecimal threshold, String units, String source, String windowId) {
}
