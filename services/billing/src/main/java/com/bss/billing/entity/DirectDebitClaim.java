package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One bill's direct-debit claim: sent to the bank on the mandate, settled
 * when the settlement file comes home carrying the same KID. One claim per
 * bill, ever — the cycle run is idempotent by construction.
 */
@Entity
@Table(name = "direct_debit_claim")
public class DirectDebitClaim {

    public static final String REQUESTED = "requested";
    public static final String SETTLED = "settled";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "mandate_id", nullable = false)
    private String mandateId;

    @Column(name = "bill_id", nullable = false)
    private String billId;

    @Column(name = "bill_no", nullable = false)
    private String billNo;

    @Column(nullable = false)
    private String kid;

    @Column(name = "amount_value", nullable = false)
    private BigDecimal amountValue;

    @Column(name = "amount_unit")
    private String amountUnit;

    @Column(nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMandateId() { return mandateId; }
    public void setMandateId(String mandateId) { this.mandateId = mandateId; }
    public String getBillId() { return billId; }
    public void setBillId(String billId) { this.billId = billId; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getKid() { return kid; }
    public void setKid(String kid) { this.kid = kid; }
    public BigDecimal getAmountValue() { return amountValue; }
    public void setAmountValue(BigDecimal amountValue) { this.amountValue = amountValue; }
    public String getAmountUnit() { return amountUnit; }
    public void setAmountUnit(String amountUnit) { this.amountUnit = amountUnit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(OffsetDateTime requestedAt) { this.requestedAt = requestedAt; }
    public OffsetDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(OffsetDateTime settledAt) { this.settledAt = settledAt; }
}
