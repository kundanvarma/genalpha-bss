package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The grading partner's verdict: the value it stands behind, its grade and a note. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GradingRequest(BigDecimal finalValue, String partnerRef, String finalGrade, String note) {
}
