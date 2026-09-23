package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The tenant's installation calendar: what windows exist, how far ahead, and
 * where capacity comes from ({@code capacityMode} is flat, roster or provider).
 * {@code lastUpdate} is null until the operator saves the calendar for the
 * first time and the map wrote that null, so only the three provider keys are
 * NON_NULL.
 */
@JsonPropertyOrder({"tenantId", "timezone", "workingDays", "slotStarts", "slotHours", "daysAhead",
        "defaultCapacity", "rosterSize", "capacityMode", "provider", "providerUrl",
        "providerSecretRef", "providerCategory", "lastUpdate", "@type"})
public record ScheduleConfigView(
        String tenantId,
        String timezone,
        List<String> workingDays,
        List<String> slotStarts,
        int slotHours,
        int daysAhead,
        int defaultCapacity,
        long rosterSize,
        String capacityMode,
        String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String providerUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String providerSecretRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String providerCategory,
        OffsetDateTime lastUpdate) {

    @JsonProperty("@type")
    public String atType() {
        return "ScheduleConfig";
    }
}
