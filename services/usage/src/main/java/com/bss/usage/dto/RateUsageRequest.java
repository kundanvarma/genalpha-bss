package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;

/** POST /rateUsage {relatedPartyId, periodStart, periodEnd} and /rateUsageBatch {relatedPartyIds, ...}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RateUsageRequest(String relatedPartyId, List<String> relatedPartyIds,
        LocalDate periodStart, LocalDate periodEnd) {
}
