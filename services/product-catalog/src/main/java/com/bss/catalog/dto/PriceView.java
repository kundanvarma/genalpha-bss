package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * A price as the configuration space shows it: the charge, its period and
 * unit, its window (and whether we are inside it), the algorithm it names and
 * the picks it applies to. Algorithms and conditions are the TMF620 objects.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "priceType", "price", "recurringChargePeriodType", "unitOfMeasure", "validFor", "inWindow",
        "pricingLogicAlgorithm", "appliesWhen"})
public record PriceView(String id, String name, String priceType, Money price, String recurringChargePeriodType,
        Quantity unitOfMeasure, TimePeriod validFor, Boolean inWindow, List<Map<String, Object>> pricingLogicAlgorithm,
        List<Map<String, Object>> appliesWhen) {
}
