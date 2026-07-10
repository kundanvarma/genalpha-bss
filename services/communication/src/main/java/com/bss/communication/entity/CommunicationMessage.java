package com.bss.communication.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "communication_message")
public class CommunicationMessage {

    public static final String SENT = "sent";
    public static final String READ = "read";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "href")
    private String href;

    @Column(name = "subject")
    private String subject;

    @Column(name = "content")
    private String content;

    @Column(name = "message_type")
    private String messageType;

    @Column(name = "status")
    private String status;

    @Column(name = "receiver_party_id")
    private String receiverPartyId;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(name = "source_event_type")
    private String sourceEventType;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public CommunicationMessage() {
    }

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

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReceiverPartyId() {
        return receiverPartyId;
    }

    public void setReceiverPartyId(String receiverPartyId) {
        this.receiverPartyId = receiverPartyId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getSourceEventType() {
        return sourceEventType;
    }

    public void setSourceEventType(String sourceEventType) {
        this.sourceEventType = sourceEventType;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
