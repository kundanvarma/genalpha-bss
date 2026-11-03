package com.bss.intelligence.llm;

/**
 * A governed completion's text and the model that WROTE it — the route that
 * served the call: the tier it ran on, and the tenant's fallback when the first
 * choice was down. A draft's provider/model marker (#422) reads it here, never
 * from a separate "which model is configured" lookup that may name another tier.
 */
public record GovernedAnswer(String text, String provider, String model) {
}
