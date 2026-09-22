package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The deviation sweep: alerts tripped this ISO week, and how many teams were told. */
@JsonPropertyOrder({"fired", "notified", "isoWeek"})
public record VocSweepReceipt(int fired, int notified, String isoWeek) {
}
