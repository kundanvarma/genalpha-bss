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

    @Column(name = "expiry_months", nullable = false)
    private int expiryMonths;

    @Column(name = "voucher_percent", nullable = false)
    private int voucherPercent = 10;

    @Column(name = "points_per_voucher", nullable = false)
    private int pointsPerVoucher = 200;

    @Column(name = "silver_threshold", nullable = false)
    private long silverThreshold = 500;

    @Column(name = "gold_threshold", nullable = false)
    private long goldThreshold = 2000;

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
    public int getExpiryMonths() { return expiryMonths; }
    public void setExpiryMonths(int v) { this.expiryMonths = v; }
    public int getVoucherPercent() { return voucherPercent; }
    public void setVoucherPercent(int v) { this.voucherPercent = v; }
    public int getPointsPerVoucher() { return pointsPerVoucher; }
    public void setPointsPerVoucher(int v) { this.pointsPerVoucher = v; }
    public long getSilverThreshold() { return silverThreshold; }
    public void setSilverThreshold(long v) { this.silverThreshold = v; }
    public long getGoldThreshold() { return goldThreshold; }
    public void setGoldThreshold(long v) { this.goldThreshold = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
