package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One free window on the shop's slot grid: when, and how many visits it still holds. */
@JsonPropertyOrder({"validFor", "remaining"})
public record SlotView(TimeWindow validFor, long remaining) {
}
