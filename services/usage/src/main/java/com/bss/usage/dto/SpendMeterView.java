package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/**
 * One monetary meter face: spend cap, content-services cap + barring, or
 * the roaming limit. The content face carries {@code barred} and the
 * statutory floor; the roaming face carries {@code continueElected}.
 */
@JsonPropertyOrder({"partyId", "meterType", "enabled", "barred", "lowestSelectableLimit", "limit", "notifyAtPct",
        "blockOnBreach", "accrued", "blocked", "continueElected", "period", "@type"})
public record SpendMeterView(String partyId, String meterType, boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean barred,
        @JsonInclude(JsonInclude.Include.NON_NULL) Money lowestSelectableLimit,
        @JsonInclude(JsonInclude.Include.NON_NULL) Money limit,
        BigDecimal notifyAtPct, boolean blockOnBreach, Money accrued, boolean blocked,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean continueElected,
        String period, @JsonProperty("@type") String type) {
}
