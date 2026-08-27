package com.bss.basemigration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The operator's stated intent for moving part of the installed base:
 * source→target offering matrix, eligibility rules, trigger, jurisdiction
 * pack — plus the rehearsal receipt without which it cannot arm.
 */
@Entity
@Table(name = "migration_plan")
public class MigrationPlan {

    public static final String DRAFT = "draft";
    public static final String SIMULATED = "simulated";
    public static final String ARMED = "armed";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String DONE = "done";

    public static final String TRIGGER_BULK = "bulk";
    public static final String TRIGGER_AGE = "age-threshold";
    public static final String TRIGGER_PROMO = "promo-expiry";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    @Column(name = "matrix_json", nullable = false, length = 8000)
    private String matrixJson;

    @Column(name = "eligibility_json", length = 4000)
    private String eligibilityJson;

    @Column(name = "trigger_json", length = 2000)
    private String triggerJson;

    @Column(name = "jurisdiction_json", length = 2000)
    private String jurisdictionJson;

    @Column(name = "grandfathered_json", length = 8000)
    private String grandfatheredJson;

    @Column(name = "notice_days", nullable = false)
    private int noticeDays;

    @Column(name = "simulation_ref", length = 64)
    private String simulationRef;

    @Column(name = "simulation_attached_at")
    private OffsetDateTime simulationAttachedAt;

    @Column(name = "max_orders_per_run", nullable = false)
    private int maxOrdersPerRun;

    @Column(name = "breaker_threshold", nullable = false)
    private int breakerThreshold;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getMatrixJson() { return matrixJson; }
    public void setMatrixJson(String matrixJson) { this.matrixJson = matrixJson; }
    public String getEligibilityJson() { return eligibilityJson; }
    public void setEligibilityJson(String eligibilityJson) { this.eligibilityJson = eligibilityJson; }
    public String getTriggerJson() { return triggerJson; }
    public void setTriggerJson(String triggerJson) { this.triggerJson = triggerJson; }
    public String getJurisdictionJson() { return jurisdictionJson; }
    public void setJurisdictionJson(String jurisdictionJson) { this.jurisdictionJson = jurisdictionJson; }
    public String getGrandfatheredJson() { return grandfatheredJson; }
    public void setGrandfatheredJson(String grandfatheredJson) { this.grandfatheredJson = grandfatheredJson; }
    public int getNoticeDays() { return noticeDays; }
    public void setNoticeDays(int noticeDays) { this.noticeDays = noticeDays; }
    public String getSimulationRef() { return simulationRef; }
    public void setSimulationRef(String simulationRef) { this.simulationRef = simulationRef; }
    public OffsetDateTime getSimulationAttachedAt() { return simulationAttachedAt; }
    public void setSimulationAttachedAt(OffsetDateTime simulationAttachedAt) { this.simulationAttachedAt = simulationAttachedAt; }
    public int getMaxOrdersPerRun() { return maxOrdersPerRun; }
    public void setMaxOrdersPerRun(int maxOrdersPerRun) { this.maxOrdersPerRun = maxOrdersPerRun; }
    public int getBreakerThreshold() { return breakerThreshold; }
    public void setBreakerThreshold(int breakerThreshold) { this.breakerThreshold = breakerThreshold; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
