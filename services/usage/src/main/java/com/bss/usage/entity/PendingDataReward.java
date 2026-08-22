package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A reward waiting for its meter — parked, never lost. */
@Entity
@Table(name = "pending_data_reward")
public class PendingDataReward {

    @Id
    @Column(name = "reward_id", nullable = false, updatable = false, length = 80)
    private String rewardId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "gb", nullable = false, precision = 6, scale = 2)
    private BigDecimal gb;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getRewardId() { return rewardId; }
    public void setRewardId(String v) { this.rewardId = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public BigDecimal getGb() { return gb; }
    public void setGb(BigDecimal v) { this.gb = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
