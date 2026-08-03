package com.bss.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One envelope owed to one listener — retried, never silently lost. */
@Entity
@Table(name = "hub_delivery")
public class HubDelivery {

    public static final String PENDING = "pending";
    public static final String DELIVERED = "delivered";
    public static final String DEAD = "dead";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "subscription_id")
    private String subscriptionId;

    @Column(name = "event_type")
    private String eventType;

    private String payload;

    private String status;

    private int attempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String v) { this.subscriptionId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime v) { this.nextAttemptAt = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
