package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** One thing a staff member did on a desk — never what they saw, never a customer. Props are the desk's own document (scrubbed before storage). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeskEventInput(String event, String session, String desk, String target, JsonNode props) {
}
