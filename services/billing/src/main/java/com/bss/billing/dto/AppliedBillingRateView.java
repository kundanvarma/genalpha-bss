package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** TMF678 AppliedCustomerBillingRate: one line of a bill. */
@JsonPropertyOrder({"id", "href", "name", "@type", "type", "taxExcludedAmount", "appliedTax", "isBilled",
        "forParty", "bill", "date"})
public record AppliedBillingRateView(String id, String href, String name,
        @JsonProperty("@type") String type,
        @JsonProperty("type") String rateType,
        Money taxExcludedAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<AppliedTax> appliedTax,
        boolean isBilled,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef forParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) EntityRef bill,
        String date) {

    /** TMF678 appliedTax: only when the catalog price declared a rate for this line. */
    @JsonPropertyOrder({"taxCategory", "taxRate"})
    public record AppliedTax(String taxCategory, BigDecimal taxRate) {
    }
}
