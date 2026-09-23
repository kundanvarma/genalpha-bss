package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The dealer channel: agreements, starter kits, counter sales, the money page, the leaderboard. */
public final class DealerDtos {

    private DealerDtos() {
    }

    @JsonPropertyOrder({"id", "dealerOrgId", "name", "commission", "@type"})
    public record DealerAgreementView(String id, String dealerOrgId, String name, Money commission,
            @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"id", "activationCode", "iccid", "store", "status", "activatedAt", "@type"})
    public record StarterKitView(String id, String activationCode, String iccid, String store, String status,
            String activatedAt, @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"productOrderId", "customerId"})
    public record SaleReceipt(String productOrderId, String customerId) {
    }

    @JsonPropertyOrder({"productOrderId", "iccid"})
    public record KitActivationReceipt(String productOrderId, String iccid) {
    }

    @JsonPropertyOrder({"productOrderId", "activated", "commission"})
    public record OrderStatus(String productOrderId, boolean activated, List<CommissionView> commission) {
    }

    @JsonPropertyOrder({"id", "store", "offeringName", "device", "amount", "status", "reason", "accruedAt",
            "hardensAt", "@type"})
    public record CommissionView(String id, String store, String offeringName, String device, Money amount,
            String status, String reason, String accruedAt, String hardensAt, @JsonProperty("@type") String type) {
    }

    /** The money page: entries newest first, totals per status. */
    @JsonPropertyOrder({"dealer", "commissionPerActivation", "totals", "entries"})
    public record CommissionPage(String dealer, Money commissionPerActivation, Map<String, BigDecimal> totals,
            List<CommissionView> entries) {
    }

    @JsonPropertyOrder({"dealerOrgId", "store", "activations", "commission", "unit", "rank"})
    public record LeaderboardRow(String dealerOrgId, String store, long activations, BigDecimal commission,
            String unit, int rank) {
    }

    /* ---------- requests ---------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgreementRequest(String dealerOrgId, String name, String clientId, Money commission) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KitBatchRequest(Integer count, String store) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaleRequest(String customerEmail, String store, String offeringId, String offeringName,
            String device) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KitActivationRequest(String code, String offeringId, String offeringName) {
    }
}
