package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The intent for one DecisionPoint, as configuration: objective, constraints, actions, exploration, autonomy, fallback. */
@Entity
@Table(name = "learning_contract")
public class LearningContract {

    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "tenant_id", length = 64)
    private String tenantId;
    @Column(name = "decision_point", length = 64)
    private String decisionPoint;
    @Column(length = 40)
    private String objective;
    @Column(name = "secondary_metrics", length = 500)
    private String secondaryMetrics;
    @Column(length = 1000)
    private String guardrails;
    @Column(name = "allowed_actions", length = 1000)
    private String allowedActions;
    @Column(name = "exploration_max_percent")
    private Integer explorationMaxPercent;
    @Column(length = 8)
    private String autonomy;
    @Column(name = "fallback_action", length = 120)
    private String fallbackAction;
    private boolean enabled = true;
    @Column(length = 1000)
    private String notes;
    private int version = 1;
    @Column(name = "updated_by", length = 120)
    private String updatedBy;
    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getDecisionPoint() { return decisionPoint; }
    public void setDecisionPoint(String v) { this.decisionPoint = v; }
    public String getObjective() { return objective; }
    public void setObjective(String v) { this.objective = v; }
    public String getSecondaryMetrics() { return secondaryMetrics; }
    public void setSecondaryMetrics(String v) { this.secondaryMetrics = v; }
    public String getGuardrails() { return guardrails; }
    public void setGuardrails(String v) { this.guardrails = v; }
    public String getAllowedActions() { return allowedActions; }
    public void setAllowedActions(String v) { this.allowedActions = v; }
    public Integer getExplorationMaxPercent() { return explorationMaxPercent; }
    public void setExplorationMaxPercent(Integer v) { this.explorationMaxPercent = v; }
    public String getAutonomy() { return autonomy; }
    public void setAutonomy(String v) { this.autonomy = v; }
    public String getFallbackAction() { return fallbackAction; }
    public void setFallbackAction(String v) { this.fallbackAction = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
