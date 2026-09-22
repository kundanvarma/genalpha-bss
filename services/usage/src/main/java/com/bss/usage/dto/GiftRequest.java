package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /gift: whole GB to a family member by id, or to any number the plan's giftScope reaches. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GiftRequest(String receiverId, String receiverPhone, BigDecimal amount) {
}
