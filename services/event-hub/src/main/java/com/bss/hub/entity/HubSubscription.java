package com.bss.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A listener's standing order: where to call, and for which events. */
@Entity
@Table(name = "hub_subscription")
public class HubSubscription {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String callback;

    @Column(name = "event_types_json")
    private String eventTypesJson;

    private boolean active;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCallback() { return callback; }
    public void setCallback(String v) { this.callback = v; }
    public String getEventTypesJson() { return eventTypesJson; }
    public void setEventTypesJson(String v) { this.eventTypesJson = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
