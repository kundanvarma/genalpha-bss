package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Money the bank says arrived but no bill cleanly claims — the classic
 * accounts-receivable "unapplied cash" queue. Never dropped, never
 * guessed at: parked with its reason for a human to resolve.
 */
@Entity
@Table(name = "unapplied_remittance")
public class UnappliedRemittance {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "batch_ref")
    private String batchRef;

    private String reference;

    @Column(name = "amount_value", nullable = false)
    private BigDecimal amountValue;

    @Column(name = "amount_unit", nullable = false)
    private String amountUnit;

    @Column(nullable = false)
    private String reason;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getBatchRef() { return batchRef; }
    public void setBatchRef(String batchRef) { this.batchRef = batchRef; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public BigDecimal getAmountValue() { return amountValue; }
    public void setAmountValue(BigDecimal amountValue) { this.amountValue = amountValue; }
    public String getAmountUnit() { return amountUnit; }
    public void setAmountUnit(String amountUnit) { this.amountUnit = amountUnit; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
}
