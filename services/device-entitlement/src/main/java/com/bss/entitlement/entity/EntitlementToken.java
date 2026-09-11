package com.bss.entitlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A token the ECS issued after a successful EAP-AKA: the phone presents it
 * on later requests instead of re-running the SIM challenge (TS.43 "Token"). */
@Entity
@Table(name = "entitlement_token")
public class EntitlementToken {

    @Id
    @Column(length = 96)
    private String token;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 16)
    private String imsi;

    @Column(name = "terminal_id", length = 64)
    private String terminalId;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public String getToken() { return token; }
    public void setToken(String v) { this.token = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getImsi() { return imsi; }
    public void setImsi(String v) { this.imsi = v; }
    public String getTerminalId() { return terminalId; }
    public void setTerminalId(String v) { this.terminalId = v; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime v) { this.issuedAt = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
}
