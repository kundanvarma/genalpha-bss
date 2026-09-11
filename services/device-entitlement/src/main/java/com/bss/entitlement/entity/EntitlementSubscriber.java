package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The binding between a SIM identity and a line: which IMSI belongs to which
 * party, service and plan. The BSS writes it (activation, SIM swap, plan
 * change); the ECS reads it on every device request. Entitlement DECISIONS
 * are never stored here — they are computed from the plan and the line's
 * state, so a plan change is live on the next check-in.
 */
@Entity
@Table(name = "entitlement_subscriber")
public class EntitlementSubscriber {

    public static final String ACTIVE = "active";
    public static final String SUSPENDED = "suspended";
    public static final String TERMINATED = "terminated";

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 16)
    private String imsi;

    @Column(length = 20)
    private String msisdn;

    @Column(length = 24)
    private String iccid;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "service_id", length = 64)
    private String serviceId;

    @Column(name = "offering_id", length = 64)
    private String offeringId;

    @Column(nullable = false, length = 16)
    private String status = ACTIVE;

    /** The line is provisioned in the IMS core (VoLTE/VoWiFi/SMSoIP can be ENABLED, not PROVISIONING). */
    @Column(name = "ims_provisioned", nullable = false)
    private boolean imsProvisioned = true;

    /** VoWiFi's emergency address is on file (TS.43 AddrStatus). */
    @Column(name = "emergency_address_confirmed", nullable = false)
    private boolean emergencyAddressConfirmed;

    /** The VoWiFi terms were accepted (TS.43 TC_Status). */
    @Column(name = "terms_accepted", nullable = false)
    private boolean termsAccepted;

    /** JSON {feature: true|false} overriding the plan for THIS line (a barring, a trial). */
    @Column(name = "feature_overrides", length = 2000)
    private String featureOverrides;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getImsi() { return imsi; }
    public void setImsi(String v) { this.imsi = v; }
    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String v) { this.msisdn = v; }
    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { this.serviceId = v; }
    public String getOfferingId() { return offeringId; }
    public void setOfferingId(String v) { this.offeringId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public boolean isImsProvisioned() { return imsProvisioned; }
    public void setImsProvisioned(boolean v) { this.imsProvisioned = v; }
    public boolean isEmergencyAddressConfirmed() { return emergencyAddressConfirmed; }
    public void setEmergencyAddressConfirmed(boolean v) { this.emergencyAddressConfirmed = v; }
    public boolean isTermsAccepted() { return termsAccepted; }
    public void setTermsAccepted(boolean v) { this.termsAccepted = v; }
    public String getFeatureOverrides() { return featureOverrides; }
    public void setFeatureOverrides(String v) { this.featureOverrides = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
