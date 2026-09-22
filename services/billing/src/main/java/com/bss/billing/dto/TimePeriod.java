package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;

/**
 * TMF TimePeriod. Billing periods are whole days, so the ends are written
 * as the bill stores them (ISO dates); a client may PATCH date-times in.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"startDateTime", "endDateTime"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimePeriod(String startDateTime, String endDateTime) {

    public static TimePeriod ofDates(LocalDate start, LocalDate end) {
        return new TimePeriod(String.valueOf(start), String.valueOf(end));
    }
}
