package com.bss.devicecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Blacklist-seam stub. EIR/GSMA IMEI blacklisting is network-side; the
 * BSS's role is to ORIGINATE the lost/stolen signal (SIM-block listener or
 * POST /deviceFlag), remember it, and quote a flagged IMEI at zero. A real
 * registry feed is a deployment adapter, not a schema change.
 */
@Entity
@Table(name = "device_flag")
public class DeviceFlag {

    public static final String BLACKLISTED = "blacklisted";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "imei", nullable = false, length = 32)
    private String imei;

    @Column(name = "flag", nullable = false, length = 24)
    private String flag;

    @Column(name = "reason", length = 64)
    private String reason;

    /** What raised it — a SIM-block event id, a staff action, an import. */
    @Column(name = "source_ref", length = 128)
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getImei() { return imei; }
    public void setImei(String v) { this.imei = v; }
    public String getFlag() { return flag; }
    public void setFlag(String v) { this.flag = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
