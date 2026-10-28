package com.bss.benefit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A partner's invoice for one month (#390): what the partner says it is owed, and when it was paid. */
@Entity
@Table(name = "partner_invoice")
public class PartnerInvoice {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "partner", nullable = false)
    private String partner;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "reference", nullable = false)
    private String reference;

    @Column(name = "amount", nullable = false)
    private java.math.BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "recorded_at", nullable = false)
    private java.time.OffsetDateTime recordedAt;

    @Column(name = "paid_at")
    private java.time.OffsetDateTime paidAt;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPartner() {
        return partner;
    }

    public void setPartner(String partner) {
        this.partner = partner;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public java.math.BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(java.math.BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public java.time.OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(java.time.OffsetDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }

    public java.time.OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(java.time.OffsetDateTime paidAt) {
        this.paidAt = paidAt;
    }
}
