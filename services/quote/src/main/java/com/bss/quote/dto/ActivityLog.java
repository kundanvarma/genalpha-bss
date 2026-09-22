package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The deal's activity log after a beat was added, newest first. */
@JsonPropertyOrder({"opportunityId", "activities"})
public record ActivityLog(String opportunityId, List<ActivityView> activities) {
}
