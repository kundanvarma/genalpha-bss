package com.bss.intelligence.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "care_chat_message")
public class CareChatMessage {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public CareChatMessage() { }

    public CareChatMessage(String sessionId, String tenantId, String author, String body) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.author = author;
        this.body = body;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }
    public String getAuthor() { return author; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
