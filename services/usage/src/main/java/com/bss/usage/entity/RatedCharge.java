package com.bss.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "rated_charge")
public class RatedCharge {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "name")
    private String name;

    @Column(name = "amount_value")
    private BigDecimal amountValue;

    @Column(name = "amount_unit")
    private String amountUnit;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public RatedCharge() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getAmountValue() {
        return amountValue;
    }

    public void setAmountValue(BigDecimal amountValue) {
        this.amountValue = amountValue;
    }

    public String getAmountUnit() {
        return amountUnit;
    }

    public void setAmountUnit(String amountUnit) {
        this.amountUnit = amountUnit;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
