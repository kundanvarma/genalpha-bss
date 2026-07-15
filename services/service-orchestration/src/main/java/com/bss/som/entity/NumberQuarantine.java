package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A released MSISDN on the aging shelf — auditable, never re-issued
 * straight into circulation. */
@Entity
@Table(name = "number_quarantine")
public class NumberQuarantine {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "number", nullable = false, length = 64)
    private String number;

    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getNumber() { return number; }
    public void setNumber(String v) { this.number = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { this.serviceId = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime v) { this.releasedAt = v; }
}
