package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "customer_bill")
public class CustomerBill {

    public static final String NEW = "new";
    public static final String SETTLED = "settled";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "bill_no")
    private String billNo;

    @Column(name = "state")
    private String state;

    @Column(name = "amount_due_value")
    private BigDecimal amountDueValue;

    @Column(name = "amount_due_unit")
    private String amountDueUnit;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "payment")
    private String paymentJson;

    @Column(name = "bill_date")
    private OffsetDateTime billDate;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public CustomerBill() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public BigDecimal getAmountDueValue() {
        return amountDueValue;
    }

    public void setAmountDueValue(BigDecimal amountDueValue) {
        this.amountDueValue = amountDueValue;
    }

    public String getAmountDueUnit() {
        return amountDueUnit;
    }

    public void setAmountDueUnit(String amountDueUnit) {
        this.amountDueUnit = amountDueUnit;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getPaymentJson() {
        return paymentJson;
    }

    public void setPaymentJson(String paymentJson) {
        this.paymentJson = paymentJson;
    }

    public OffsetDateTime getBillDate() {
        return billDate;
    }

    public void setBillDate(OffsetDateTime billDate) {
        this.billDate = billDate;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
