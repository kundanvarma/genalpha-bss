package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** The redemption: pending until the joiner's first order completes, then the reward lands for both. */
@JsonPropertyOrder({"code", "status", "rewardGb", "note"})
public record RedeemReceipt(String code, String status, BigDecimal rewardGb, String note) {
}
