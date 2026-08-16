package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A routing band: a lead scoring at or above min_score routes to this
 *  assignee (the highest band it clears wins). */
@Entity
@Table(name = "lead_routing_rule")
public class LeadRoutingRule {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "min_score", nullable = false)
    private int minScore;

    @Column(name = "assignee", nullable = false, length = 255)
    private String assignee;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public int getMinScore() { return minScore; }
    public void setMinScore(int v) { this.minScore = v; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String v) { this.assignee = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
