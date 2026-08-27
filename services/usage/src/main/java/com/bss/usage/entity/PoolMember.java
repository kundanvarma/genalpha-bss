package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One subscription attached to a household pool, with optional per-member
 * soft (notify) and hard (stop drawing) limits in GB per cycle.
 */
@Entity
@Table(name = "pool_member")
public class PoolMember {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "pool_id")
    private String poolId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "soft_limit_value")
    private BigDecimal softLimitValue;

    @Column(name = "hard_limit_value")
    private BigDecimal hardLimitValue;

    @Column(name = "consumed_value")
    private BigDecimal consumedValue;

    @Column(name = "consumed_period")
    private LocalDate consumedPeriod;

    private String status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPoolId() { return poolId; }
    public void setPoolId(String poolId) { this.poolId = poolId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public BigDecimal getSoftLimitValue() { return softLimitValue; }
    public void setSoftLimitValue(BigDecimal softLimitValue) { this.softLimitValue = softLimitValue; }
    public BigDecimal getHardLimitValue() { return hardLimitValue; }
    public void setHardLimitValue(BigDecimal hardLimitValue) { this.hardLimitValue = hardLimitValue; }
    public BigDecimal getConsumedValue() { return consumedValue; }
    public void setConsumedValue(BigDecimal consumedValue) { this.consumedValue = consumedValue; }
    public LocalDate getConsumedPeriod() { return consumedPeriod; }
    public void setConsumedPeriod(LocalDate consumedPeriod) { this.consumedPeriod = consumedPeriod; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
