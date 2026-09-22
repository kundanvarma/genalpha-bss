package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** A sample decision: the context the point would see, the actions it could take, and whom it is about. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DryRunRequest(JsonNode context, List<String> candidates, String subjectId, String fallbackAction) {
}
