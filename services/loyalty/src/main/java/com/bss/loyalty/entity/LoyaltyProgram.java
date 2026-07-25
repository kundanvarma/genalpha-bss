package com.bss.loyalty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The tenant's loyalty program — DATA, editable like a policy rule. */
@Entity
@Table(name = "loyalty_program")
public class LoyaltyProgram {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "earn_points_per_currency", nullable = false)
    private BigDecimal earnPointsPerCurrency = BigDecimal.ONE;

    @Column(name = "points_per_gb", nullable = false)
    private int pointsPerGb = 100;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public BigDecimal getEarnPointsPerCurrency() { return earnPointsPerCurrency; }
    public void setEarnPointsPerCurrency(BigDecimal v) { this.earnPointsPerCurrency = v; }
    public int getPointsPerGb() { return pointsPerGb; }
    public void setPointsPerGb(int v) { this.pointsPerGb = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
