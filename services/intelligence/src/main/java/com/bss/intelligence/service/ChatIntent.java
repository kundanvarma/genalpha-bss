package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What the customer wants right now on a live chat, with a reply to consider. */
@JsonPropertyOrder({"intent", "confidence", "summary", "reply", "provider", "model"})
public record ChatIntent(String intent, double confidence, String summary, String reply,
        String provider, String model) {
}
