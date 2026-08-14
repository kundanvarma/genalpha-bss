package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A brand MENTION pulled from a social platform — the inbound-insight side of
 * social. The BSS listens to what's said about the brand and scores sentiment,
 * so a marketer sees share-of-voice and mood, not just outbound campaigns.
 * Idempotent per (tenant, platform, external_id).
 */
@Entity
@Table(name = "social_mention")
public class SocialMention {

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

    @Column(name = "text", length = 2000)
    private String text;

    /** positive | negative | neutral — scored on ingest (stub classifier; the
     * intelligence LLM is the production scorer). */
    @Column(name = "sentiment")
    private String sentiment;

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
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
