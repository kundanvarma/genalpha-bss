package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A purchased data top-up: extra allowance for one party's current billing
 * period, recorded when a completed order carries a boost-flagged offering.
 * One row per (order, spec) — at-least-once event delivery stays idempotent.
 */
@Entity
@Table(name = "allowance_boost")
public class AllowanceBoost {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "usage_spec_name")
    private String usageSpecName;

    @Column(name = "boost_value")
    private BigDecimal boostValue;

    private String units;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /** NULL = purchased top-up; 'gift:to:<id>' / 'gift:from:<name>' / 'rollover'. */
    private String source;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public String getUsageSpecName() { return usageSpecName; }
    public void setUsageSpecName(String usageSpecName) { this.usageSpecName = usageSpecName; }
    public BigDecimal getBoostValue() { return boostValue; }
    public void setBoostValue(BigDecimal boostValue) { this.boostValue = boostValue; }
    public String getUnits() { return units; }
    public void setUnits(String units) { this.units = units; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String productOrderId) { this.productOrderId = productOrderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
