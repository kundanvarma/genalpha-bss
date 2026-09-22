package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One grading verdict on a valuation: who graded, what grade, the value and its delta over the estimate. */
@JsonPropertyOrder({"id", "partnerRef", "finalGrade", "finalValue", "delta", "note", "createdAt"})
public record GradingEventView(String id, String partnerRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String finalGrade,
        BigDecimal finalValue, BigDecimal delta,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        String createdAt) {
}
