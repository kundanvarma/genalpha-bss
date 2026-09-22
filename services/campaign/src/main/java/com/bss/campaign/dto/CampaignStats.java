package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The measurement readout: reached / held out / conversions per side / lift,
 * the attributed revenue per exposed customer, and — when the campaign has
 * arms — the A/B readout with its honest z-test verdict.
 */
@JsonPropertyOrder({"campaignId", "reached", "heldOut", "conversions", "treatedRate", "holdoutRate", "liftPoints",
        "conversionWindowDays", "note", "revenue", "arms"})
public record CampaignStats(
        String campaignId,
        long reached,
        long heldOut,
        Conversions conversions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double treatedRate,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double holdoutRate,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double liftPoints,
        int conversionWindowDays,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        @JsonInclude(JsonInclude.Include.NON_NULL) Revenue revenue,
        @JsonInclude(JsonInclude.Include.NON_NULL) ArmReadout arms) {

    /** Monthly money per side, per exposed customer, and the lift between them. */
    @JsonPropertyOrder({"treated", "holdout", "treatedPerCustomer", "holdoutPerCustomer", "liftPerCustomer", "basis"})
    public record Revenue(
            BigDecimal treated,
            BigDecimal holdout,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal treatedPerCustomer,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal holdoutPerCustomer,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal liftPerCustomer,
            String basis) {
    }

    /** Per-arm sent / conversions / rate, a leader, and the verdict of a two-proportion z-test at 95 %. */
    @JsonPropertyOrder({"arms", "leader", "verdict"})
    public record ArmReadout(
            List<ArmStat> arms,
            @JsonInclude(JsonInclude.Include.NON_NULL) String leader,
            @JsonInclude(JsonInclude.Include.NON_NULL) String verdict) {
    }

    /** One arm's numbers; {@code rate} is null until the arm has been sent to anyone. */
    @JsonPropertyOrder({"name", "subject", "sent", "conversions", "rate"})
    public record ArmStat(String name, String subject, long sent, long conversions, Double rate) {
    }
}
