package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "campaign")
public class Campaign {

    public static final String ACTIVE = "active";
    public static final String PAUSED = "paused";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "trigger_event_type", nullable = false, length = 128)
    private String triggerEventType;

    @Column(name = "trigger_state", length = 64)
    private String triggerState;

    @Column(name = "message_subject", nullable = false)
    private String messageSubject;

    @Column(name = "message_content", nullable = false, length = 2000)
    private String messageContent;

    @Column(name = "promotion_code", length = 64)
    private String promotionCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerEventType() { return triggerEventType; }
    public void setTriggerEventType(String triggerEventType) { this.triggerEventType = triggerEventType; }
    public String getTriggerState() { return triggerState; }
    public void setTriggerState(String triggerState) { this.triggerState = triggerState; }
    public String getMessageSubject() { return messageSubject; }
    public void setMessageSubject(String messageSubject) { this.messageSubject = messageSubject; }
    public String getMessageContent() { return messageContent; }
    public void setMessageContent(String messageContent) { this.messageContent = messageContent; }
    public String getPromotionCode() { return promotionCode; }
    public void setPromotionCode(String promotionCode) { this.promotionCode = promotionCode; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
