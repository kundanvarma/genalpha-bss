package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The tenant's dunning policy: ordered steps over the account's aging
 * ({offsetDays from due date, action, templateId, feeType, feeAmount}),
 * entry threshold, promise allowance, reconnection fee, write-off threshold.
 * Writes are validated against the country statutory pack — a tenant can be
 * softer than the law, never harder.
 */
@Entity
@Table(name = "dunning_policy")
public class DunningPolicy {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    /** Days from bill date to due date — aging starts when this passes. */
    @Column(name = "payment_term_days", nullable = false)
    private int paymentTermDays = 14;

    @Column(name = "entry_threshold", nullable = false)
    private BigDecimal entryThreshold;

    @Column(name = "currency", length = 8)
    private String currency;

    /** JSON array of ordered steps. */
    @Column(name = "steps_json", nullable = false, length = 4000)
    private String stepsJson;

    /** Contractual, not statutory — a tenant may zero it. */
    @Column(name = "reconnection_fee", nullable = false)
    private BigDecimal reconnectionFee = BigDecimal.ZERO;

    @Column(name = "write_off_threshold", nullable = false)
    private BigDecimal writeOffThreshold = BigDecimal.ZERO;

    @Column(name = "promise_max_per_period", nullable = false)
    private int promiseMaxPerPeriod = 2;

    @Column(name = "promise_period_days", nullable = false)
    private int promisePeriodDays = 90;

    @Column(name = "promise_max_days", nullable = false)
    private int promiseMaxDays = 14;

    @Column(name = "auto_refund_threshold", nullable = false)
    private BigDecimal autoRefundThreshold = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public int getPaymentTermDays() { return paymentTermDays; }
    public void setPaymentTermDays(int v) { this.paymentTermDays = v; }
    public BigDecimal getEntryThreshold() { return entryThreshold; }
    public void setEntryThreshold(BigDecimal v) { this.entryThreshold = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String v) { this.stepsJson = v; }
    public BigDecimal getReconnectionFee() { return reconnectionFee; }
    public void setReconnectionFee(BigDecimal v) { this.reconnectionFee = v; }
    public BigDecimal getWriteOffThreshold() { return writeOffThreshold; }
    public void setWriteOffThreshold(BigDecimal v) { this.writeOffThreshold = v; }
    public int getPromiseMaxPerPeriod() { return promiseMaxPerPeriod; }
    public void setPromiseMaxPerPeriod(int v) { this.promiseMaxPerPeriod = v; }
    public int getPromisePeriodDays() { return promisePeriodDays; }
    public void setPromisePeriodDays(int v) { this.promisePeriodDays = v; }
    public int getPromiseMaxDays() { return promiseMaxDays; }
    public void setPromiseMaxDays(int v) { this.promiseMaxDays = v; }
    public BigDecimal getAutoRefundThreshold() { return autoRefundThreshold; }
    public void setAutoRefundThreshold(BigDecimal v) { this.autoRefundThreshold = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
