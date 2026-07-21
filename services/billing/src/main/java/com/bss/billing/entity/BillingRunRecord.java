package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * THE RUN HAS A FACE: one row per billing run — when it started, what it
 * did, and how it ended. A crashed run leaves its RUNNING row behind as
 * evidence; the next run marks it superseded and simply continues,
 * because every bill already cut is its own resume marker.
 */
@Entity
@Table(name = "billing_run")
public class BillingRunRecord {

    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String SUPERSEDED = "superseded";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "accounts_total", nullable = false)
    private int accountsTotal;

    @Column(name = "bills_created", nullable = false)
    private int billsCreated;

    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getAccountsTotal() {
        return accountsTotal;
    }

    public void setAccountsTotal(int accountsTotal) {
        this.accountsTotal = accountsTotal;
    }

    public int getBillsCreated() {
        return billsCreated;
    }

    public void setBillsCreated(int billsCreated) {
        this.billsCreated = billsCreated;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
