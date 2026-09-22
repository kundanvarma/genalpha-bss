package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** A completed gift: the DataGiftEvent's resource and the caller's receipt. */
@JsonPropertyOrder({"id", "giver", "receiver", "amount", "units", "usageType"})
public record DataGift(String id, PartyName giver, PartyName receiver, BigDecimal amount, String units,
        String usageType) {

    @JsonPropertyOrder({"id", "name"})
    public record PartyName(String id, String name) {
    }
}
