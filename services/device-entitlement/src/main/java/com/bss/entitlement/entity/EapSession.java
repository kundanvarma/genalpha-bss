package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A pending EAP-AKA challenge: what we sent, what we expect back. */
@Entity
@Table(name = "eap_session")
public class EapSession {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "eap_id", nullable = false, length = 160)
    private String eapId;

    @Column(nullable = false, length = 16)
    private String imsi;

    @Column(nullable = false)
    private int identifier;

    @Column(name = "xres_hex", nullable = false, length = 64)
    private String xresHex;

    @Column(name = "kaut_hex", nullable = false, length = 64)
    private String kautHex;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getEapId() { return eapId; }
    public void setEapId(String v) { this.eapId = v; }
    public String getImsi() { return imsi; }
    public void setImsi(String v) { this.imsi = v; }
    public int getIdentifier() { return identifier; }
    public void setIdentifier(int v) { this.identifier = v; }
    public String getXresHex() { return xresHex; }
    public void setXresHex(String v) { this.xresHex = v; }
    public String getKautHex() { return kautHex; }
    public void setKautHex(String v) { this.kautHex = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
