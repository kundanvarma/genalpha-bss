package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Enrol parties by hand: who, and the tokens their messages may use ({@code {{order.id}}}…) as an open document. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnrollmentRequest(List<String> partyIds, JsonNode context) {
}
