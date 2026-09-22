package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One member's draw on the pool this period, with the caps the owner set. */
@JsonPropertyOrder({"partyId", "consumedGB", "softLimitGB", "hardLimitGB", "status"})
public record PoolMemberView(String partyId, BigDecimal consumedGB,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal softLimitGB,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal hardLimitGB,
        String status) {
}
