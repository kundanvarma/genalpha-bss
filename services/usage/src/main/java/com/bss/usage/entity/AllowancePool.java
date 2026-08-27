package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A household's shared data bucket: the owner (payer) funds it, member
 * subscriptions draw it down per usage record. consumed_value/consumed_period
 * are the denormalized real-time counters, reset lazily at cycle roll.
 */
@Entity
@Table(name = "allowance_pool")
public class AllowancePool {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String name;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    /** NULL = any GB usage spec; set = only that spec draws the pool. */
    @Column(name = "usage_spec_name")
    private String usageSpecName;

    @Column(name = "pool_value")
    private BigDecimal poolValue;

    private String units;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public String getUsageSpecName() { return usageSpecName; }
    public void setUsageSpecName(String usageSpecName) { this.usageSpecName = usageSpecName; }
    public BigDecimal getPoolValue() { return poolValue; }
    public void setPoolValue(BigDecimal poolValue) { this.poolValue = poolValue; }
    public String getUnits() { return units; }
    public void setUnits(String units) { this.units = units; }
    public BigDecimal getConsumedValue() { return consumedValue; }
    public void setConsumedValue(BigDecimal consumedValue) { this.consumedValue = consumedValue; }
    public LocalDate getConsumedPeriod() { return consumedPeriod; }
    public void setConsumedPeriod(LocalDate consumedPeriod) { this.consumedPeriod = consumedPeriod; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
