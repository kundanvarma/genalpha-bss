package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/** Who was enrolled by hand and which arm each was dealt ({@code holdout}, an arm name, or empty for the one message). */
@JsonPropertyOrder({"journeyId", "enrolled", "dealt"})
public record EnrollmentReceipt(String journeyId, int enrolled, Map<String, String> dealt) {
}
