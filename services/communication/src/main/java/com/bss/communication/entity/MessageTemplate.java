package com.bss.communication.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A reusable message template: one named piece of copy, per channel, with a
 * body per locale and personalization tokens ({{party.firstName}} etc.).
 * Journeys and campaigns reference it instead of pasting inline strings, so
 * copy is authored once, localized, and changed in one place.
 */
@Entity
@Table(name = "message_template")
public class MessageTemplate {

    @Id
    private String id;
    private String href;

    @Column(name = "tenant_id")
    private String tenantId;

    private String name;

    /** inApp | email | sms | push — the channel this copy is written for. */
    private String channel;

    /** JSON: { "en": {"subject": "...", "body": "..."}, "nb": {...} }. */
    @Column(length = 8000)
    private String locales;

    /** Optional TMF671 promotion this template carries (feeds {{promotion.*}}). */
    @Column(name = "promotion_ref")
    private String promotionRef;

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
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getLocales() { return locales; }
    public void setLocales(String locales) { this.locales = locales; }
    public String getPromotionRef() { return promotionRef; }
    public void setPromotionRef(String promotionRef) { this.promotionRef = promotionRef; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
