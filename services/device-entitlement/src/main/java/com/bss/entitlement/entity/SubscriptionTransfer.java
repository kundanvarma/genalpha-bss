package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A primary-device eSIM transfer requested through ODSA (TS.43 ap2009,
 * ManageSubscription TRANSFER): the subscription moves from the old device to
 * a new eSIM — recorded here, the SIM swap itself is the orchestrator's. */
@Entity
@Table(name = "subscription_transfer")
public class SubscriptionTransfer {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 16)
    private String imsi;

    @Column(name = "old_terminal_id", length = 64)
    private String oldTerminalId;

    @Column(name = "target_terminal_id", length = 64)
    private String targetTerminalId;

    @Column(name = "target_eid", length = 40)
    private String targetEid;

    @Column(name = "new_iccid", length = 24)
    private String newIccid;

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
    public String getOldTerminalId() { return oldTerminalId; }
    public void setOldTerminalId(String v) { this.oldTerminalId = v; }
    public String getTargetTerminalId() { return targetTerminalId; }
    public void setTargetTerminalId(String v) { this.targetTerminalId = v; }
    public String getTargetEid() { return targetEid; }
    public void setTargetEid(String v) { this.targetEid = v; }
    public String getNewIccid() { return newIccid; }
    public void setNewIccid(String v) { this.newIccid = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getActivationCode() { return activationCode; }
    public void setActivationCode(String v) { this.activationCode = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
