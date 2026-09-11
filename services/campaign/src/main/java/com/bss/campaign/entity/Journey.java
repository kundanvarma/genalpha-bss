package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A journey: trigger + ordered steps (message | wait) + a conversion event
 * that is also the ALWAYS-ON EXIT RULE. Steps are data — the console edits
 * a list, not a diagram.
 */
@Entity
@Table(name = "journey")
public class Journey {

    public static final String ACTIVE = "active";
    public static final String PAUSED = "paused";
    /** The full lifecycle; only ACTIVE triggers and runs. draft/scheduled sit
     *  before go-live, archived is a soft delete hidden from the default list. */
    public static final java.util.Set<String> LIFECYCLE =
            java.util.Set.of("draft", "scheduled", ACTIVE, PAUSED, "archived");
    public static final String ARCHIVED = "archived";

    @Id
    private String id;
    private String href;

    @Column(name = "tenant_id")
    private String tenantId;

    private String name;
    private String status;

    @Column(name = "trigger_event_type")
    private String triggerEventType;

    @Column(name = "trigger_state")
    private String triggerState;

    @Column(name = "segment_name")
    private String segmentName;

    @Column(length = 4000)
    private String steps;

    @Column(name = "conversion_event")
    private String conversionEvent;

    @Column(name = "holdout_percent")
    private int holdoutPercent;

    /** Arbitration policy: when two journeys would message the same customer
     *  in the same moment, the higher priority wins and the other is held. */
    @Column(name = "priority")
    private int priority;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** Stamped when steps change after launch — the stats honesty marker. */
    @Column(name = "steps_edited_at")
    private OffsetDateTime stepsEditedAt;

    /** A/B arms: message variants [{name, subject, content}] for the first message step. */
    @Column(length = 4000)
    private String arms;
    /** Shift traffic to the winning arm on evidence (never below the floor per arm). */
    @Column(name = "auto_tune")
    private boolean autoTune;
    /** Current traffic weights per arm, JSON {name: percent}. */
    @Column(name = "arm_weights", length = 1000)
    private String armWeights;
    /** The tuning ledger: every decision with its evidence, newest last, JSON list. */
    @Column(name = "tuning_log", length = 8000)
    private String tuningLog;

    /** {@code marketing} (default) or {@code transactional}: a service
     * notice the customer expects — never parked by quiet hours, never
     * counted against the marketing frequency budget. */
    @Column(name = "category", length = 32)
    private String category = MARKETING;

    public static final String MARKETING = "marketing";
    public static final String TRANSACTIONAL = "transactional";

    public String getCategory() { return category == null ? MARKETING : category; }
    public void setCategory(String v) { this.category = v == null || v.isBlank() ? MARKETING : v; }
    public boolean isTransactional() { return TRANSACTIONAL.equals(getCategory()); }

    public String getArms() { return arms; }
    public void setArms(String v) { this.arms = v; }
    public boolean isAutoTune() { return autoTune; }
    public void setAutoTune(boolean v) { this.autoTune = v; }
    public String getArmWeights() { return armWeights; }
    public void setArmWeights(String v) { this.armWeights = v; }
    public String getTuningLog() { return tuningLog; }
    public void setTuningLog(String v) { this.tuningLog = v; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerEventType() { return triggerEventType; }
    public void setTriggerEventType(String v) { this.triggerEventType = v; }
    public String getTriggerState() { return triggerState; }
    public void setTriggerState(String v) { this.triggerState = v; }
    public String getSegmentName() { return segmentName; }
    public void setSegmentName(String v) { this.segmentName = v; }
    public String getSteps() { return steps; }
    public void setSteps(String steps) { this.steps = steps; }
    public String getConversionEvent() { return conversionEvent; }
    public void setConversionEvent(String v) { this.conversionEvent = v; }
    public int getHoldoutPercent() { return holdoutPercent; }
    public void setHoldoutPercent(int v) { this.holdoutPercent = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
    public OffsetDateTime getStepsEditedAt() { return stepsEditedAt; }
    public void setStepsEditedAt(OffsetDateTime v) { this.stepsEditedAt = v; }
}
