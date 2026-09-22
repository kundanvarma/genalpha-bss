package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Open the 14-day withdrawal on an agreement; a deduction exists only with a recorded return grade. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawalRequest(String agreementId, BigDecimal deduction, String returnGrade) {
}
