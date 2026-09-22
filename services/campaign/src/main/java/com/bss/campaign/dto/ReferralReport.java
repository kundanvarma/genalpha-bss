package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** The staff readout: every conversion, the program's counts, and the honest cost line — what it has PAID, in data. */
@JsonPropertyOrder({"@type", "conversions", "rewarded", "pending", "held", "rewardCostGb", "rows"})
public record ReferralReport(
        @JsonProperty("@type") String type,
        int conversions,
        long rewarded,
        long pending,
        long held,
        BigDecimal rewardCostGb,
        List<Row> rows) {

    @JsonPropertyOrder({"code", "referrerPartyId", "joinerPartyId", "status", "createdAt"})
    public record Row(String code, String referrerPartyId, String joinerPartyId, String status,
            OffsetDateTime createdAt) {
    }
}
