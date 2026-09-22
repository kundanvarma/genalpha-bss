package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.OffsetDateTime;

/** What ops sees of the auto-refresh scheduler: activity + live JVM heap, in one place. */
@JsonPropertyOrder({"enabled", "intervalMs", "maxPerRun", "totalRuns", "totalRefreshed", "totalErrors", "lastRunAt",
        "lastDurationMs", "lastRefreshed", "heapUsedMb", "heapMaxMb"})
public record RefreshStatus(boolean enabled, long intervalMs, int maxPerRun, long totalRuns, long totalRefreshed,
        long totalErrors, OffsetDateTime lastRunAt, long lastDurationMs, long lastRefreshed, long heapUsedMb, long heapMaxMb) {

    /** A manual sweep: the status plus how many it refreshed. */
    @JsonPropertyOrder({"status", "refreshed"})
    public record RunReceipt(@JsonUnwrapped RefreshStatus status, int refreshed) {
    }
}
