package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/** The record plus the sentences an auditor reads first. */
@JsonPropertyOrder({"decision", "receipt"})
public record DecisionReceipt(@JsonUnwrapped DecisionView decision, List<String> receipt) {
}
