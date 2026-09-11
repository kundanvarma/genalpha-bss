package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A device that has checked in with the ECS: what it is, which SIM it
 * carried, and the push token it registered for server-initiated
 * re-configuration (TS.43 §2.6). */
@Entity
@Table(name = "entitlement_device")
public class EntitlementDevice {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "terminal_id", nullable = false, length = 64)
    private String terminalId;

    @Column(length = 16)
    private String imsi;

    @Column(length = 64)
    private String vendor;

    @Column(length = 64)
    private String model;

    @Column(name = "sw_version", length = 64)
    private String swVersion;

    @Column(name = "notif_token", length = 512)
    private String notifToken;

    @Column(name = "notif_action")
    private Integer notifAction;

    @Column(name = "last_apps", length = 256)
    private String lastApps;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getTerminalId() { return terminalId; }
    public void setTerminalId(String v) { this.terminalId = v; }
    public String getImsi() { return imsi; }
    public void setImsi(String v) { this.imsi = v; }
    public String getVendor() { return vendor; }
    public void setVendor(String v) { this.vendor = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getSwVersion() { return swVersion; }
    public void setSwVersion(String v) { this.swVersion = v; }
    public String getNotifToken() { return notifToken; }
    public void setNotifToken(String v) { this.notifToken = v; }
    public Integer getNotifAction() { return notifAction; }
    public void setNotifAction(Integer v) { this.notifAction = v; }
    public String getLastApps() { return lastApps; }
    public void setLastApps(String v) { this.lastApps = v; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime v) { this.lastSeenAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
