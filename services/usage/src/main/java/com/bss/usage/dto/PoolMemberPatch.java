package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** PATCH /allowancePool/{id}/member/{partyId}: a cap absent stays, a cap sent as null is removed. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PoolMemberPatch(JsonNode softLimitGB, JsonNode hardLimitGB) {

    public static BigDecimal value(JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.asText());
    }
}
