package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The CSR copilot's read of a customer's 360: what is going on, what to do next. */
@JsonPropertyOrder({"summary", "nextActions", "provider", "model"})
public record CustomerSummary(String summary, List<String> nextActions, String provider, String model) {
}
