package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One ledger row of the MVNO's wholesale cost: a period's units of one usage
 * type at the host's rate. The re-rate receipt ({@code rerateCount},
 * {@code lastReratedAt}) appears once the late-CDR loop has moved it.
 */
@JsonPropertyOrder({"id", "periodStart", "usageSpecName", "totalUnits", "unit", "wholesaleRate", "amount", "currency",
        "hostPartyId", "status", "rerateCount", "lastReratedAt", "@type"})
public record WholesaleLedgerView(String id, String periodStart, String usageSpecName, BigDecimal totalUnits,
        String unit, BigDecimal wholesaleRate, BigDecimal amount, String currency, String hostPartyId, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer rerateCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime lastReratedAt,
        @JsonProperty("@type") String type) {
}
