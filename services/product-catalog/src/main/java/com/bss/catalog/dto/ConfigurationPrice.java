package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * TMF760 v5 ConfigurationPrice: a price on a configuration — declared (in the
 * space, with its conditions) or evaluated (in a check, with the quantity it
 * was multiplied by). The amount rides as {@code price.dutyFreeAmount}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"name", "priceType", "productOfferingPrice", "unitOfMeasure", "recurringChargePeriod", "price", "quantity",
        "prodSpecCharValueUse", "@type"})
public record ConfigurationPrice(String name, String priceType, EntityRef productOfferingPrice, Quantity unitOfMeasure,
        Quantity recurringChargePeriod, Amount price, Integer quantity, List<Map<String, Object>> prodSpecCharValueUse,
        @JsonProperty("@type") String type) {

    public record Amount(Money dutyFreeAmount) {
    }
}
