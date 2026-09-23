package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Close the books through this date. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PeriodCloseRequest(@JsonProperty("through") String through) {
}
