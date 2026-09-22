package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Conversions per side of the holdout: the treated group and the control group. */
@JsonPropertyOrder({"treated", "holdout"})
public record Conversions(long treated, long holdout) {
}
