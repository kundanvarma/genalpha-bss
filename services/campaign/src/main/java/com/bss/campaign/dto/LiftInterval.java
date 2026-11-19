package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The range the true lift lies in, in percentage points, at 95 % — and it
 * stays 95 % however often the page is opened (see AlwaysValid). A range
 * that spans zero means the lift has not been measured yet.
 */
@JsonPropertyOrder({"low", "high"})
public record LiftInterval(double low, double high) {

    public boolean spansZero() {
        return low <= 0 && high >= 0;
    }
}
