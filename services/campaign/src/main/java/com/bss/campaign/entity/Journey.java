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

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
