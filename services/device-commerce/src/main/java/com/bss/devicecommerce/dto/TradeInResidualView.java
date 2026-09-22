package com.bss.devicecommerce.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One row of the staff-curated residual table: what a device model is worth at an age. */
@JsonPropertyOrder({"id", "deviceRef", "ageMonths", "baseValue", "currency", "@type"})
public record TradeInResidualView(String id, String deviceRef, int ageMonths, BigDecimal baseValue,
        String currency, @JsonProperty("@type") String type) {

    public static final String TYPE = "TradeInResidual";
}
