package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A proposed change to live financial configuration, as a screen reads it.
 *
 * <p>{@code summary} is the whole change in one sentence of business language,
 * written here rather than in a channel: a console asks what changed, it does
 * not work it out. {@code nextStep} is the rung the change is waiting on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "postingKey", "account", "summary", "reason", "state", "nextStep",
        "findings", "postingsUsing", "currentCode", "currentName", "currentValue",
        "proposedCode", "proposedName", "proposedValue",
        "draftedBy", "draftedAt", "validatedBy", "validatedAt",
        "approvedBy", "approvedAt", "activatedBy", "activatedAt", "@type"})
public record ConfigChangeView(
        @JsonProperty("id") String id,
        @JsonProperty("postingKey") String postingKey,
        @JsonProperty("account") String account,
        @JsonProperty("summary") String summary,
        @JsonProperty("reason") String reason,
        @JsonProperty("state") String state,
        @JsonProperty("nextStep") String nextStep,
        @JsonProperty("findings") List<ChangeFinding> findings,
        @JsonProperty("postingsUsing") long postingsUsing,
        @JsonProperty("currentCode") String currentCode,
        @JsonProperty("currentName") String currentName,
        @JsonProperty("currentValue") BigDecimal currentValue,
        @JsonProperty("proposedCode") String proposedCode,
        @JsonProperty("proposedName") String proposedName,
        @JsonProperty("proposedValue") BigDecimal proposedValue,
        @JsonProperty("draftedBy") String draftedBy,
        @JsonProperty("draftedAt") OffsetDateTime draftedAt,
        @JsonProperty("validatedBy") String validatedBy,
        @JsonProperty("validatedAt") OffsetDateTime validatedAt,
        @JsonProperty("approvedBy") String approvedBy,
        @JsonProperty("approvedAt") OffsetDateTime approvedAt,
        @JsonProperty("activatedBy") String activatedBy,
        @JsonProperty("activatedAt") OffsetDateTime activatedAt,
        @JsonProperty("@type") String type) {
}
