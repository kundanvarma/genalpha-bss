package com.bss.benefit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** What the operator owes a partner for one active benefit in one month (#360). */
@Entity
@Table(name = "partner_cost")
public class PartnerCost {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "allocation_id", nullable = false)
    private String allocationId;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "partner", nullable = false)
    private String partner;

    @Column(name = "offering_name", nullable = false)
    private String offeringName;

    @Column(name = "amount", nullable = false)
    private java.math.BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "recorded_at", nullable = false)
    private java.time.OffsetDateTime recordedAt;

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

    public String getAllocationId() {
        return allocationId;
    }

    public void setAllocationId(String allocationId) {
        this.allocationId = allocationId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getPartner() {
        return partner;
    }

    public void setPartner(String partner) {
        this.partner = partner;
    }

    public String getOfferingName() {
        return offeringName;
    }

    public void setOfferingName(String offeringName) {
        this.offeringName = offeringName;
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
}
