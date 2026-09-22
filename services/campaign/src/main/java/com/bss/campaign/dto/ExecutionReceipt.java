package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A segment blast: which audience was swept and how many newcomers were reached (or held out). */
@JsonPropertyOrder({"campaignId", "audience", "reached"})
public record ExecutionReceipt(String campaignId, String audience, int reached) {
}
