package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One monetary meter per (party, face) per cycle. meter_type is the legal
 * face: 'spend' (subscription cap), 'content' (content-services cap + free
 * barring), 'roaming' (the default financial limit with cut-off). Accrual
 * columns are per-cycle and reset lazily; warned_pct/breached dedup events.
 */
@Entity
@Table(name = "spend_meter")
public class SpendMeter {

    public static final String SPEND = "spend";
    public static final String CONTENT = "content";
    public static final String ROAMING = "roaming";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "meter_type")
    private String meterType;

    private boolean enabled;

    /** Content face only: the free, always-available barring switch. */
    private boolean barred;

    @Column(name = "limit_value")
    private BigDecimal limitValue;

    private String currency;

    @Column(name = "notify_at_pct")
    private BigDecimal notifyAtPct;

    @Column(name = "block_on_breach")
    private boolean blockOnBreach;

    @Column(name = "accrued_value")
    private BigDecimal accruedValue;

    @Column(name = "accrual_period")
    private LocalDate accrualPeriod;

    @Column(name = "warned_pct")
    private BigDecimal warnedPct;

    private boolean breached;

    private boolean blocked;

    /** Roaming face only: the customer explicitly chose to keep roaming past the limit. */
    @Column(name = "continue_elected")
    private boolean continueElected;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getMeterType() { return meterType; }
    public void setMeterType(String meterType) { this.meterType = meterType; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isBarred() { return barred; }
    public void setBarred(boolean barred) { this.barred = barred; }
    public BigDecimal getLimitValue() { return limitValue; }
    public void setLimitValue(BigDecimal limitValue) { this.limitValue = limitValue; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getNotifyAtPct() { return notifyAtPct; }
    public void setNotifyAtPct(BigDecimal notifyAtPct) { this.notifyAtPct = notifyAtPct; }
    public boolean isBlockOnBreach() { return blockOnBreach; }
    public void setBlockOnBreach(boolean blockOnBreach) { this.blockOnBreach = blockOnBreach; }
    public BigDecimal getAccruedValue() { return accruedValue; }
    public void setAccruedValue(BigDecimal accruedValue) { this.accruedValue = accruedValue; }
    public LocalDate getAccrualPeriod() { return accrualPeriod; }
    public void setAccrualPeriod(LocalDate accrualPeriod) { this.accrualPeriod = accrualPeriod; }
    public BigDecimal getWarnedPct() { return warnedPct; }
    public void setWarnedPct(BigDecimal warnedPct) { this.warnedPct = warnedPct; }
    public boolean isBreached() { return breached; }
    public void setBreached(boolean breached) { this.breached = breached; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public boolean isContinueElected() { return continueElected; }
    public void setContinueElected(boolean continueElected) { this.continueElected = continueElected; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
