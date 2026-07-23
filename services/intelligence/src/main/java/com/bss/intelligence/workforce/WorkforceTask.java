package com.bss.intelligence.workforce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One CLAIMED unit of digital-worker work. The id is stable and derived
 * (kind~subjectRef), so the same backlog item can never be worked twice;
 * status runs claimed → completed | escalated. Self-reported cost fields
 * exist because a worker brings its OWN model — the ledger labels them as
 * the worker's word, never as control-plane truth.
 */
@Entity
@Table(name = "workforce_task")
public class WorkforceTask {

    public static final String CLAIMED = "claimed";
    public static final String COMPLETED = "completed";
    public static final String ESCALATED = "escalated";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "subject_ref", nullable = false)
    private String subjectRef;

    private String summary;

    @Column(nullable = false)
    private String status;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    private String outcome;

    @Column(name = "self_tokens")
    private Integer selfTokens;

    @Column(name = "self_cost_micros")
    private Long selfCostMicros;

    @Column(name = "self_model")
    private String selfModel;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public void setSubjectRef(String subjectRef) {
        this.subjectRef = subjectRef;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public OffsetDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(OffsetDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(OffsetDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Integer getSelfTokens() {
        return selfTokens;
    }

    public void setSelfTokens(Integer selfTokens) {
        this.selfTokens = selfTokens;
    }

    public Long getSelfCostMicros() {
        return selfCostMicros;
    }

    public void setSelfCostMicros(Long selfCostMicros) {
        this.selfCostMicros = selfCostMicros;
    }

    public String getSelfModel() {
        return selfModel;
    }

    public void setSelfModel(String selfModel) {
        this.selfModel = selfModel;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
