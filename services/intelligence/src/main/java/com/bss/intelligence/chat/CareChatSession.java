package com.bss.intelligence.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "care_chat_session")
public class CareChatSession {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "party_id")
    private String partyId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status = "open";

    @Column(name = "ticket_id")
    private String ticketId;

    /** When the customer was told they are talking to an AI (Art. 50(1), #422): at open, before any answer. */
    @Column(name = "ai_disclosed_at")
    private Instant aiDisclosedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }

    public Instant getAiDisclosedAt() {
        return aiDisclosedAt;
    }

    public void setAiDisclosedAt(Instant aiDisclosedAt) {
        this.aiDisclosedAt = aiDisclosedAt;
    }
}
