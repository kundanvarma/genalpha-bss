package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** POST /allowancePool: the owner (staff name one; a customer is the owner), the size, an optional usage type. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PoolRequest(String ownerPartyId, String name, String usageType, BigDecimal poolGB) {
}
