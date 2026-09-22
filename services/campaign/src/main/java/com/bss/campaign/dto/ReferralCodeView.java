package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** A customer's own referral code and how it is doing: joiners, rewards paid, rewards pending. */
@JsonPropertyOrder({"code", "rewardGb", "joined", "rewarded", "pending"})
public record ReferralCodeView(String code, BigDecimal rewardGb, int joined, long rewarded, long pending) {
}
