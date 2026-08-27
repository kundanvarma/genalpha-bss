package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One collection case per financial account (the owner party): the account's
 * aggregated overdue position and where it stands on the dunning ladder.
 * Holds (promise-to-pay, amount-scoped dispute, hardship) are orthogonal to
 * the state; a cure resets the ladder without deleting the history row.
 */
@Entity
@Table(name = "collection_case")
public class CollectionCase {

    public static final String CURRENT = "current";
    public static final String REMINDED = "reminded";
    public static final String WARNED = "warned";
    public static final String RESTRICTED = "restricted";
    public static final String SUSPENDED = "suspended";
    public static final String TERMINATED = "terminated";
    public static final String WRITTEN_OFF = "writtenOff";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "overdue_value", nullable = false)
    private BigDecimal overdueValue = BigDecimal.ZERO;

    @Column(name = "oldest_due_at")
    private OffsetDateTime oldestDueAt;

    /** The next policy step to execute (0-based into the ordered steps). */
    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    /** Fee-bearing reminders already charged for THIS delinquency. */
    @Column(name = "fee_count", nullable = false)
    private int feeCount;

    /** When the payment demand + advance warning went out — the statutory
     * clock any restriction or suspension measures from. */
    @Column(name = "warned_at")
    private OffsetDateTime warnedAt;

    @Column(name = "promise_value")
    private BigDecimal promiseValue;

    @Column(name = "promise_due_at")
    private OffsetDateTime promiseDueAt;

    @Column(name = "promises_made", nullable = false)
    private int promisesMade;

    @Column(name = "promise_window_start")
    private OffsetDateTime promiseWindowStart;

    /** Amount-scoped dispute hold: only this much is frozen — the rest of
     * the balance keeps aging. */
    @Column(name = "dispute_hold_value")
    private BigDecimal disputeHoldValue;

    @Column(name = "hardship_hold", nullable = false)
    private boolean hardshipHold;

    /** JSON array of service ids we restricted/suspended — the exact set a
     * cure reinstates. */
    @Column(name = "enforced_services", length = 2000)
    private String enforcedServicesJson;

    @Column(name = "cured_at")
    private OffsetDateTime curedAt;

    @Column(name = "written_off_at")
    private OffsetDateTime writtenOffAt;

    @Column(name = "write_off_reason", length = 500)
    private String writeOffReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String v) { this.accountId = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public BigDecimal getOverdueValue() { return overdueValue; }
    public void setOverdueValue(BigDecimal v) { this.overdueValue = v; }
    public OffsetDateTime getOldestDueAt() { return oldestDueAt; }
    public void setOldestDueAt(OffsetDateTime v) { this.oldestDueAt = v; }
    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int v) { this.stepIndex = v; }
    public int getFeeCount() { return feeCount; }
    public void setFeeCount(int v) { this.feeCount = v; }
    public OffsetDateTime getWarnedAt() { return warnedAt; }
    public void setWarnedAt(OffsetDateTime v) { this.warnedAt = v; }
    public BigDecimal getPromiseValue() { return promiseValue; }
    public void setPromiseValue(BigDecimal v) { this.promiseValue = v; }
    public OffsetDateTime getPromiseDueAt() { return promiseDueAt; }
    public void setPromiseDueAt(OffsetDateTime v) { this.promiseDueAt = v; }
    public int getPromisesMade() { return promisesMade; }
    public void setPromisesMade(int v) { this.promisesMade = v; }
    public OffsetDateTime getPromiseWindowStart() { return promiseWindowStart; }
    public void setPromiseWindowStart(OffsetDateTime v) { this.promiseWindowStart = v; }
    public BigDecimal getDisputeHoldValue() { return disputeHoldValue; }
    public void setDisputeHoldValue(BigDecimal v) { this.disputeHoldValue = v; }
    public boolean isHardshipHold() { return hardshipHold; }
    public void setHardshipHold(boolean v) { this.hardshipHold = v; }
    public String getEnforcedServicesJson() { return enforcedServicesJson; }
    public void setEnforcedServicesJson(String v) { this.enforcedServicesJson = v; }
    public OffsetDateTime getCuredAt() { return curedAt; }
    public void setCuredAt(OffsetDateTime v) { this.curedAt = v; }
    public OffsetDateTime getWrittenOffAt() { return writtenOffAt; }
    public void setWrittenOffAt(OffsetDateTime v) { this.writtenOffAt = v; }
    public String getWriteOffReason() { return writeOffReason; }
    public void setWriteOffReason(String v) { this.writeOffReason = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
