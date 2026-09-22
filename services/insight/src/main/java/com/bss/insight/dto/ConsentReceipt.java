package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The consent choice as recorded. */
@JsonPropertyOrder({"visitorId", "analytics", "personalization"})
public record ConsentReceipt(String visitorId, boolean analytics, boolean personalization) {
}
