package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST advanceClock: how many days to move a sandbox's clock (default 30). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdvanceClockRequest(Integer days) {

    public int daysOrDefault() {
        return days == null ? 30 : days;
    }
}
