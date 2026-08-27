package com.bss.devicecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Angrerett (EU 2011/83): unconditional withdrawal within 14 days of
 * physical receipt on distance sales. The refund includes standard
 * shipping; a deduction is lawful only for documented diminished value
 * beyond shop-style inspection — hence the mini-grading fields.
 */
@Entity
@Table(name = "withdrawal_case")
public class WithdrawalCase {

    public static final String OPEN = "open";
    public static final String REFUNDED = "refunded";
    public static final String REJECTED = "rejected";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "order_ref", length = 64)
    private String orderRef;

    @Column(name = "agreement_ref", nullable = false, length = 36)
    private String agreementRef;

    /** When the 14 days started counting (delivery, or activation fallback). */
    @Column(name = "clock_start", nullable = false)
    private OffsetDateTime clockStart;

    /** The return's own mini-grading — the documented basis for a deduction. */
    @Column(name = "return_grade", length = 16)
    private String returnGrade;

    @Column(name = "deduction", precision = 12, scale = 2)
    private BigDecimal deduction;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_ref", length = 64)
    private String refundRef;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getHref() { return href; }
    public void setHref(String v) { this.href = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getOrderRef() { return orderRef; }
    public void setOrderRef(String v) { this.orderRef = v; }
    public String getAgreementRef() { return agreementRef; }
    public void setAgreementRef(String v) { this.agreementRef = v; }
    public OffsetDateTime getClockStart() { return clockStart; }
    public void setClockStart(OffsetDateTime v) { this.clockStart = v; }
    public String getReturnGrade() { return returnGrade; }
    public void setReturnGrade(String v) { this.returnGrade = v; }
    public BigDecimal getDeduction() { return deduction; }
    public void setDeduction(BigDecimal v) { this.deduction = v; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal v) { this.refundAmount = v; }
    public String getRefundRef() { return refundRef; }
    public void setRefundRef(String v) { this.refundRef = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
