package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * An inbound DIRECT MESSAGE pulled from a social platform — a private 1:1 the
 * care team owns (a support ask, a complaint), distinct from a public mention.
 * Scored for sentiment + intent on ingest; when it needs a human, insight emits
 * a SocialCareTicketRequested event and the trouble-ticket service opens a case.
 * Idempotent per (tenant, platform, external_id).
 */
@Entity
@Table(name = "social_dm")
public class SocialDm {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "platform")
    private String platform;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "author")
    private String author;

    /** The sender's platform handle (@name) — how care replies to them. */
    @Column(name = "handle")
    private String handle;

    @Column(name = "text", length = 2000)
    private String text;

    /** positive | negative | neutral — the mood of the message. */
    @Column(name = "sentiment")
    private String sentiment;

    /** true when the DM asks for help / reports a problem (needs a ticket). */
    @Column(name = "needs_care")
    private boolean needsCare;

    /** id of the trouble ticket this DM opened (null until one is requested). */
    @Column(name = "ticket_requested")
    private boolean ticketRequested;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
    public boolean isNeedsCare() { return needsCare; }
    public void setNeedsCare(boolean needsCare) { this.needsCare = needsCare; }
    public boolean isTicketRequested() { return ticketRequested; }
    public void setTicketRequested(boolean ticketRequested) { this.ticketRequested = ticketRequested; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
