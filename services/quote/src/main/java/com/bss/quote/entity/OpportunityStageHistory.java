package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One stage a deal entered, and when — the basis for conversion, time-in-stage
 *  and cycle-time analytics. */
@Entity
@Table(name = "opportunity_stage_history")
public class OpportunityStageHistory {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "opportunity_id", nullable = false, length = 36)
    private String opportunityId;

    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    @Column(name = "entered_at", nullable = false)
    private OffsetDateTime enteredAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getOpportunityId() { return opportunityId; }
    public void setOpportunityId(String v) { this.opportunityId = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { this.stage = v; }
    public OffsetDateTime getEnteredAt() { return enteredAt; }
    public void setEnteredAt(OffsetDateTime v) { this.enteredAt = v; }
}
