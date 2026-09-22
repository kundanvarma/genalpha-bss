package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The household data pool: totals this period and, for the owner and
 * household managers, every member's draw. A plain member sees no
 * {@code member} list.
 */
@JsonPropertyOrder({"id", "href", "name", "ownerPartyId", "usageType", "poolGB", "consumedGB", "remainingGB",
        "units", "status", "@type", "member"})
public record PoolView(String id, String href, String name, String ownerPartyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String usageType,
        BigDecimal poolGB, BigDecimal consumedGB, BigDecimal remainingGB, String units, String status,
        @JsonProperty("@type") String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PoolMemberView> member) {
}
