package com.bss.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /loyaltyProgram body: every field optional — only what is given changes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoyaltyProgramRequest(
        Boolean enabled,
        BigDecimal earnPointsPerCurrency,
        Integer pointsPerGb,
        Integer expiryMonths,
        Integer voucherPercent,
        Integer pointsPerVoucher,
        Long silverThreshold,
        Long goldThreshold) {

    public static final LoyaltyProgramRequest EMPTY =
            new LoyaltyProgramRequest(null, null, null, null, null, null, null, null);
}
