package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Where a sandbox's clock stands after the move. */
@JsonPropertyOrder({"cloneId", "clockOffsetDays"})
public record ClockReceipt(String cloneId, int clockOffsetDays) {
}
