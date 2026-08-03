package com.bss.assurance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A promise broken, on the record — the raw material of compensation. */
@Entity
@Table(name = "sla_violation")
public class SlaViolation {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "agreement_id")
    private String agreementId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "problem_id")
    private String problemId;

    @Column(name = "affected_object")
    private String affectedObject;

    @Column(name = "threshold_minutes")
    private Long thresholdMinutes;

    @Column(name = "duration_minutes")
    private Long durationMinutes;

    @Column(name = "credit_amount")
    private BigDecimal creditAmount;

    private boolean credited;

    private String note;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getAgreementId() { return agreementId; }
    public void setAgreementId(String v) { this.agreementId = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getProblemId() { return problemId; }
    public void setProblemId(String v) { this.problemId = v; }
    public String getAffectedObject() { return affectedObject; }
    public void setAffectedObject(String v) { this.affectedObject = v; }
    public Long getThresholdMinutes() { return thresholdMinutes; }
    public void setThresholdMinutes(Long v) { this.thresholdMinutes = v; }
    public Long getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Long v) { this.durationMinutes = v; }
    public BigDecimal getCreditAmount() { return creditAmount; }
    public void setCreditAmount(BigDecimal v) { this.creditAmount = v; }
    public boolean isCredited() { return credited; }
    public void setCredited(boolean v) { this.credited = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
