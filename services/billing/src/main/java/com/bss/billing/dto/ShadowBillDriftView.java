package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The next invoice will differ from the last — one drift row. Also the BillDriftDetectedEvent payload. */
@JsonPropertyOrder({"id", "ownerPartyId", "billId", "offeringId", "offeringName", "billedMonthly", "currentMonthly",
        "delta", "unit", "detectedAt", "@type"})
public record ShadowBillDriftView(String id, String ownerPartyId, String billId, String offeringId,
        String offeringName, BigDecimal billedMonthly, BigDecimal currentMonthly, BigDecimal delta, String unit,
        OffsetDateTime detectedAt, @JsonProperty("@type") String type) {
}
