package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One adaptive choice, from any service, and the outcome that followed it. */
@Entity
@Table(name = "decision_log")
public class DecisionLog {

    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id")
    private String tenantId;
    @Column(name = "decision_point", length = 64)
    private String decisionPoint;
    @Column(name = "subject_type", length = 32)
    private String subjectType;
    @Column(name = "subject_id", length = 64)
    private String subjectId;
    @Column(columnDefinition = "TEXT")
    private String candidates;
    @Column(columnDefinition = "TEXT")
    private String eligible;
    @Column(columnDefinition = "TEXT")
    private String constraints;
    @Column(length = 120)
    private String action;
    @Column(precision = 7, scale = 4)
    private BigDecimal propensity;
    @Column(length = 64)
    private String policy;
    @Column(name = "policy_version", length = 16)
    private String policyVersion;
    @Column(length = 1000)
    private String reason;
    @Column(columnDefinition = "TEXT")
    private String context;
    @Column(columnDefinition = "TEXT")
    private String evidence;
    @Column(length = 8)
    private String autonomy;
    private boolean fallback;
    @Column(length = 40)
    private String source;
    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;
    @Column(length = 80)
    private String contract;
    @Column(length = 40)
    private String outcome;
    @Column(name = "outcome_value", precision = 14, scale = 2)
    private BigDecimal outcomeValue;
    @Column(name = "outcome_at")
    private OffsetDateTime outcomeAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDecisionPoint() { return decisionPoint; }
    public void setDecisionPoint(String v) { this.decisionPoint = v; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String v) { this.subjectType = v; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }
    public String getCandidates() { return candidates; }
    public void setCandidates(String v) { this.candidates = v; }
    public String getEligible() { return eligible; }
    public void setEligible(String v) { this.eligible = v; }
    public String getConstraints() { return constraints; }
    public void setConstraints(String v) { this.constraints = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public BigDecimal getPropensity() { return propensity; }
    public void setPropensity(BigDecimal v) { this.propensity = v; }
    public String getPolicy() { return policy; }
    public void setPolicy(String v) { this.policy = v; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String v) { this.policyVersion = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getContext() { return context; }
    public void setContext(String v) { this.context = v; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String v) { this.evidence = v; }
    public String getAutonomy() { return autonomy; }
    public void setAutonomy(String v) { this.autonomy = v; }
    public boolean isFallback() { return fallback; }
    public void setFallback(boolean v) { this.fallback = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime v) { this.decidedAt = v; }
    public String getContract() { return contract; }
    public void setContract(String v) { this.contract = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public BigDecimal getOutcomeValue() { return outcomeValue; }
    public void setOutcomeValue(BigDecimal v) { this.outcomeValue = v; }
    public OffsetDateTime getOutcomeAt() { return outcomeAt; }
    public void setOutcomeAt(OffsetDateTime v) { this.outcomeAt = v; }
}
