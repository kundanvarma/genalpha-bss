package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One active commitment on the obligation timeline the ERP allocates over. */
@JsonPropertyOrder({"contractId", "contractName", "partyId", "offeringId", "offeringName",
        "startDate", "endDate", "commitmentMonths", "monthsElapsed", "monthsRemaining", "@type"})
public record RevRecRow(
        @JsonProperty("contractId") String contractId,
        @JsonProperty("contractName") String contractName,
        @JsonProperty("partyId") String partyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("offeringId") String offeringId,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("offeringName") String offeringName,
        @JsonProperty("startDate") String startDate,
        @JsonProperty("endDate") String endDate,
        @JsonProperty("commitmentMonths") long commitmentMonths,
        @JsonProperty("monthsElapsed") long monthsElapsed,
        @JsonProperty("monthsRemaining") long monthsRemaining,
        @JsonProperty("@type") String type) {
}
