package com.bss.loyalty.dto;

import com.bss.loyalty.entity.LoyaltyProgram;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** The tenant's program as the marketer reads it back — data, never code. */
@JsonPropertyOrder({"enabled", "earnPointsPerCurrency", "pointsPerGb", "expiryMonths", "voucherPercent",
        "pointsPerVoucher", "silverThreshold", "goldThreshold", "@type"})
public record LoyaltyProgramView(
        boolean enabled,
        BigDecimal earnPointsPerCurrency,
        int pointsPerGb,
        int expiryMonths,
        int voucherPercent,
        int pointsPerVoucher,
        long silverThreshold,
        long goldThreshold,
        @JsonProperty("@type") String type) {

    public static LoyaltyProgramView of(LoyaltyProgram p) {
        return new LoyaltyProgramView(p.isEnabled(), p.getEarnPointsPerCurrency(), p.getPointsPerGb(),
                p.getExpiryMonths(), p.getVoucherPercent(), p.getPointsPerVoucher(),
                p.getSilverThreshold(), p.getGoldThreshold(), "LoyaltyProgramSpecification");
    }
}
