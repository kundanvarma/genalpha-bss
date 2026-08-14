package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** What an external MVNO owes the host for a period, per usage type — the host's
 *  wholesale AR, keyed uniquely by (tenant, mvno, period, usage type). */
@Entity
@Table(name = "provider_usage_ledger")
public class ProviderUsageLedger {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "mvno_party_id", nullable = false, length = 64)
    private String mvnoPartyId;

    @Column(name = "mvno_name")
    private String mvnoName;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "usage_spec_name", nullable = false, length = 64)
    private String usageSpecName;

    @Column(name = "total_units", nullable = false, precision = 18, scale = 4)
    private BigDecimal totalUnits;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public ProviderUsageLedger() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMvnoPartyId() { return mvnoPartyId; }
    public void setMvnoPartyId(String mvnoPartyId) { this.mvnoPartyId = mvnoPartyId; }
    public String getMvnoName() { return mvnoName; }
    public void setMvnoName(String mvnoName) { this.mvnoName = mvnoName; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public String getUsageSpecName() { return usageSpecName; }
    public void setUsageSpecName(String usageSpecName) { this.usageSpecName = usageSpecName; }
    public BigDecimal getTotalUnits() { return totalUnits; }
    public void setTotalUnits(BigDecimal totalUnits) { this.totalUnits = totalUnits; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
