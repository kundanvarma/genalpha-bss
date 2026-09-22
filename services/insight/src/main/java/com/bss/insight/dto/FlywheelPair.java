package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One fine-tune pair in TWIN space: the fiction in, the verified label out — zero real facts by construction. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"input", "source", "lang", "output"})
public record FlywheelPair(String input, String source, String lang, SignalClassificationView.Label output) {
}
