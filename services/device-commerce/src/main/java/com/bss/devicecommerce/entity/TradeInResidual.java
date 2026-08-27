package com.bss.devicecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Tenant-editable residual table: what a device model is worth at a given
 * age. The instant trade-in estimate reads this — staff curate it. */
@Entity
@Table(name = "trade_in_residual")
public class TradeInResidual {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "device_ref", nullable = false, length = 128)
    private String deviceRef;

    @Column(name = "age_months", nullable = false)
    private int ageMonths;

    @Column(name = "base_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseValue;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getDeviceRef() { return deviceRef; }
    public void setDeviceRef(String v) { this.deviceRef = v; }
    public int getAgeMonths() { return ageMonths; }
    public void setAgeMonths(int v) { this.ageMonths = v; }
    public BigDecimal getBaseValue() { return baseValue; }
    public void setBaseValue(BigDecimal v) { this.baseValue = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
