package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The reversing document: same information discipline as an invoice,
 * its own gapless number, a reference to the bill it credits, and a
 * REQUIRED reason. Append-only — never patched, never deleted.
 */
@Entity
@Table(name = "credit_note")
public class CreditNote {

    public static final String REDUCED = "reduced";
    public static final String REFUNDED = "refunded";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "credit_note_no")
    private String creditNoteNo;

    @Column(name = "bill_id")
    private String billId;

    @Column(name = "bill_no")
    private String billNo;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "amount_value")
    private BigDecimal amountValue;

    @Column(name = "amount_unit")
    private String amountUnit;

    private String reason;

    private String settlement;

    @Column(name = "refund_ref")
    private String refundRef;

    @Column(name = "dispute_id")
    private String disputeId;

    @Column(name = "issued_at")
    private OffsetDateTime issuedAt;

    /** The credited rate lines, snapshotted as [{id, name, amount}]. */
    @Column(name = "lines_json")
    private String linesJson;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCreditNoteNo() { return creditNoteNo; }
    public void setCreditNoteNo(String v) { this.creditNoteNo = v; }
    public String getBillId() { return billId; }
    public void setBillId(String v) { this.billId = v; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String v) { this.billNo = v; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String v) { this.ownerPartyId = v; }
    public BigDecimal getAmountValue() { return amountValue; }
    public void setAmountValue(BigDecimal v) { this.amountValue = v; }
    public String getAmountUnit() { return amountUnit; }
    public void setAmountUnit(String v) { this.amountUnit = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getSettlement() { return settlement; }
    public void setSettlement(String v) { this.settlement = v; }
    public String getRefundRef() { return refundRef; }
    public void setRefundRef(String v) { this.refundRef = v; }
    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String v) { this.disputeId = v; }
    public String getLinesJson() { return linesJson; }
    public void setLinesJson(String v) { this.linesJson = v; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime v) { this.issuedAt = v; }
}
