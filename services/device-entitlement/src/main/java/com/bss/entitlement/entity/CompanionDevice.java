package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A companion eSIM device (watch, tablet) activated on a primary line
 * through ODSA (TS.43 ap2006): eligibility → subscription → profile download. */
@Entity
@Table(name = "companion_device")
public class CompanionDevice {

    public static final String ELIGIBLE = "eligible";
    public static final String SUBSCRIBED = "subscribed";
    public static final String ACTIVE = "active";
    public static final String UNSUBSCRIBED = "unsubscribed";

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** The PRIMARY line's IMSI the companion rides on. */
    @Column(nullable = false, length = 16)
    private String imsi;

    @Column(name = "companion_terminal_id", nullable = false, length = 64)
    private String companionTerminalId;

    @Column(length = 40)
    private String eid;

    @Column(length = 24)
    private String iccid;

    @Column(length = 64)
    private String vendor;

    @Column(length = 64)
    private String model;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "activation_code", length = 256)
    private String activationCode;

    @Column(name = "matching_id", length = 64)
    private String matchingId;

    @Column(name = "smdp_address", length = 128)
    private String smdpAddress;

    /** ordered → downloading → installed | released | cancelled (from the SM-DP+'s notifications). */
    @Column(name = "profile_state", length = 16)
    private String profileState;

    public String getMatchingId() { return matchingId; }
    public void setMatchingId(String v) { this.matchingId = v; }
    public String getSmdpAddress() { return smdpAddress; }
    public void setSmdpAddress(String v) { this.smdpAddress = v; }
    public String getProfileState() { return profileState; }
    public void setProfileState(String v) { this.profileState = v; }

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
    public String getCompanionTerminalId() { return companionTerminalId; }
    public void setCompanionTerminalId(String v) { this.companionTerminalId = v; }
    public String getEid() { return eid; }
    public void setEid(String v) { this.eid = v; }
    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }
    public String getVendor() { return vendor; }
    public void setVendor(String v) { this.vendor = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getActivationCode() { return activationCode; }
    public void setActivationCode(String v) { this.activationCode = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
