package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** An audience the tenant's own analytics computed (GA4 Data API runReport row). */
@JsonPropertyOrder({"name", "size", "source"})
public record AnalyticsAudience(String name, long size, String source) {
}
