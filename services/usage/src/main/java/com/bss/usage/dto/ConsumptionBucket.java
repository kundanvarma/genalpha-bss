package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * TMF677 bucket: one (offering, usage type) meter this month — or, with a
 * {@code zone}, the zone-tagged usage read against its travel passes.
 * {@code allowedValue} appears only where a rule or a pass gives one.
 */
@JsonPropertyOrder({"id", "name", "zone", "usedValue", "units", "allowedValue"})
public record ConsumptionBucket(String id, String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String zone,
        BigDecimal usedValue, String units,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal allowedValue) {
}
