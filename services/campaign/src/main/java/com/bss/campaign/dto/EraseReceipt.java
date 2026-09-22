package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What erasure removed; marketing history has no legal hold, so nothing is retained. */
@JsonPropertyOrder({"category", "deleted", "retained"})
public record EraseReceipt(String category, int deleted, int retained) {
}
