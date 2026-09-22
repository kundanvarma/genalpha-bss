package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** Quota vs won vs weighted-open for a period: per rep, then rolled up per team — the VP's Monday view. */
@JsonPropertyOrder({"period", "owners", "byTeam"})
public record QuotaAttainment(String period, List<OwnerRow> owners, List<TeamRow> byTeam) {

    @JsonPropertyOrder({"owner", "team", "quota", "won", "weightedOpen", "attainmentPct", "coveragePct"})
    public record OwnerRow(String owner,
            @JsonInclude(JsonInclude.Include.NON_NULL) String team,
            BigDecimal quota, BigDecimal won, BigDecimal weightedOpen, double attainmentPct, double coveragePct) {
    }

    @JsonPropertyOrder({"team", "quota", "won", "weightedOpen", "attainmentPct", "coveragePct"})
    public record TeamRow(String team, BigDecimal quota, BigDecimal won, BigDecimal weightedOpen,
            double attainmentPct, double coveragePct) {
    }
}
