package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The enrolment after a hand-recorded conversion: its status and the arm it was dealt (empty for the one message). */
@JsonPropertyOrder({"journeyId", "partyId", "status", "arm"})
public record ConversionReceipt(String journeyId, String partyId, String status, String arm) {
}
