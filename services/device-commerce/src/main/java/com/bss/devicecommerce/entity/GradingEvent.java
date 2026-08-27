package com.bss.devicecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The grading partner's verdict on a mailed-in device — the audit trail
 * behind every revaluation, one row per grading. */
@Entity
@Table(name = "grading_event")
public class GradingEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "valuation_ref", nullable = false, length = 36)
    private String valuationRef;

    @Column(name = "partner_ref", length = 128)
    private String partnerRef;

    @Column(name = "final_grade", length = 16)
    private String finalGrade;

    @Column(name = "final_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalValue;

    @Column(name = "delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal delta;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getValuationRef() { return valuationRef; }
    public void setValuationRef(String v) { this.valuationRef = v; }
    public String getPartnerRef() { return partnerRef; }
    public void setPartnerRef(String v) { this.partnerRef = v; }
    public String getFinalGrade() { return finalGrade; }
    public void setFinalGrade(String v) { this.finalGrade = v; }
    public BigDecimal getFinalValue() { return finalValue; }
    public void setFinalValue(BigDecimal v) { this.finalValue = v; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal v) { this.delta = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
