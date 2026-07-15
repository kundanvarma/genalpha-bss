package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One actual marketing message to one customer — the unit the frequency
 * cap counts. Holdouts and transactional notifications are not touches. */
@Entity
@Table(name = "marketing_touch")
public class MarketingTouch {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime v) { this.sentAt = v; }
}
