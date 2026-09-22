package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Split an unpaid bill: how many parts (2-12, default 3), and when the first is due. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallmentPlanRequest(Integer installments, String firstDueAt) {

    public static final InstallmentPlanRequest EMPTY = new InstallmentPlanRequest(null, null);
}
