package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /allowancePool/{id}/member. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PoolMemberRequest(String partyId, BigDecimal softLimitGB, BigDecimal hardLimitGB) {
}
