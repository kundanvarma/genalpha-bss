package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF TimePeriod; a report period has only a start, a pass has both ends. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"startDateTime", "endDateTime"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimePeriod(String startDateTime, String endDateTime) {

    public static TimePeriod from(String start) {
        return new TimePeriod(start, null);
    }
}
