package com.bss.quote.dto;

import com.bss.quote.entity.SalesQuota;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** A rep's quota for a month, optionally under a team. */
@JsonPropertyOrder({"id", "ownerName", "quotaPeriod", "amount", "team"})
public record QuotaView(String id, String ownerName, String quotaPeriod, BigDecimal amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String team) {

    public static QuotaView of(SalesQuota q) {
        return new QuotaView(q.getId(), q.getOwnerName(), q.getQuotaPeriod(), q.getAmount(), q.getTeam());
    }
}
