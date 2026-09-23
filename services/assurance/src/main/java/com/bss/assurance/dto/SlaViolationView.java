package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A broken promise on the ledger the monthly cap is enforced against. The
 * credit is the stored {@code BigDecimal} at the column's scale — a credit of
 * {@code 0.00} says so in two decimals, and re-scaling it would move the wire.
 */
@JsonPropertyOrder({"id", "agreementId", "problemId", "affectedObject", "thresholdMinutes",
        "durationMinutes", "creditAmount", "credited", "note", "relatedParty",
        "createdAt", "@type"})
public record SlaViolationView(
        String id,
        String agreementId,
        String problemId,
        String affectedObject,
        Long thresholdMinutes,
        Long durationMinutes,
        BigDecimal creditAmount,
        boolean credited,
        String note,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<CustomerRef> relatedParty,
        OffsetDateTime createdAt) {

    @JsonProperty("@type")
    public String atType() {
        return "SlaViolation";
    }
}
