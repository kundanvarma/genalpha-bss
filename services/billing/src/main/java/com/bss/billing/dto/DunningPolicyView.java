package com.bss.billing.dto;

import com.bss.billing.service.CountryStatutoryPack;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * The tenant's dunning policy beside the statutory floor it cannot undercut.
 * {@code steps} is the ladder exactly as the tenant wrote it (declared data,
 * kept as a tree); the service parses it separately when it walks.
 */
@JsonPropertyOrder({"id", "name", "country", "paymentTermDays", "entryThreshold", "currency", "steps",
        "reconnectionFee", "writeOffThreshold", "promiseMaxPerPeriod", "promisePeriodDays", "promiseMaxDays",
        "autoRefundThreshold", "active", "statutory", "@type"})
public record DunningPolicyView(String id, String name, String country, int paymentTermDays,
        BigDecimal entryThreshold,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
        JsonNode steps, BigDecimal reconnectionFee, BigDecimal writeOffThreshold, int promiseMaxPerPeriod,
        int promisePeriodDays, int promiseMaxDays, BigDecimal autoRefundThreshold, boolean active,
        CountryStatutoryPack statutory,
        @JsonProperty("@type") String type) {
}
