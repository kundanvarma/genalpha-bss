package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * TMF921-shaped business intent: the WHAT (latency budget, bandwidth,
 * place, AI capacity), never the HOW. The orchestration layer answers
 * with feasibility and a proposal — including services the customer
 * didn't ask for but the network knows it can upsell.
 */
@Entity
@Table(name = "intent")
public class Intent {

    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String FEASIBILITY_CHECKED = "feasibilityChecked";
    public static final String INFEASIBLE = "infeasible";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "place", nullable = false, length = 128)
    private String place;

    @Column(name = "bandwidth_mbps", nullable = false)
    private long bandwidthMbps;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "ai_tokens_millions")
    private Long aiTokensMillions;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "report", length = 4000)
    private String report;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public String getPlace() { return place; }
    public void setPlace(String place) { this.place = place; }
    public long getBandwidthMbps() { return bandwidthMbps; }
    public void setBandwidthMbps(long bandwidthMbps) { this.bandwidthMbps = bandwidthMbps; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public Long getAiTokensMillions() { return aiTokensMillions; }
    public void setAiTokensMillions(Long aiTokensMillions) { this.aiTokensMillions = aiTokensMillions; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
