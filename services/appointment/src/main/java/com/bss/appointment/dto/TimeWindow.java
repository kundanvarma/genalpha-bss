package com.bss.appointment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF646 validFor: a window written as the two instants, exactly as stored. */
@JsonPropertyOrder({"startDateTime", "endDateTime"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimeWindow(String startDateTime, String endDateTime) {
}
