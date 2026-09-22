package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** A 14-day withdrawal case: when the clock started, what was deducted (with its documented grade), what was refunded and where. */
@JsonPropertyOrder({"id", "href", "agreementRef", "orderRef", "status", "clockStart", "returnGrade", "deduction",
        "refundAmount", "refundRef", "relatedParty", "@type"})
public record WithdrawalCaseView(
        String id,
        String href,
        String agreementRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String orderRef,
        String status,
        String clockStart,
        @JsonInclude(JsonInclude.Include.NON_NULL) String returnGrade,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal deduction,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal refundAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refundRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RelatedPartyRef> relatedParty,
        @JsonProperty("@type") String type) {

    public static final String TYPE = "WithdrawalCase";
}
