package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /adjust body: operator goodwill — a non-zero movement WITH its cause. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdjustRequest(String partyId, Long points, String reason) {

    public long pointsOrZero() {
        return points == null ? 0 : points;
    }
}
