package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One visitor's first-party profile. Consent is the spine: the flags here
 * gate every write and every personalization decision. */
@Entity
@Table(name = "visitor_profile")
public class VisitorProfile {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "visitor_id")
    private String visitorId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "analytics_consent")
    private boolean analyticsConsent;

    @Column(name = "personalization_consent")
    private boolean personalizationConsent;

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getVisitorId() { return visitorId; }
    public void setVisitorId(String visitorId) { this.visitorId = visitorId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public boolean isAnalyticsConsent() { return analyticsConsent; }
    public void setAnalyticsConsent(boolean v) { this.analyticsConsent = v; }
    public boolean isPersonalizationConsent() { return personalizationConsent; }
    public void setPersonalizationConsent(boolean v) { this.personalizationConsent = v; }
    public String getUtmSource() { return utmSource; }
    public void setUtmSource(String utmSource) { this.utmSource = utmSource; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
