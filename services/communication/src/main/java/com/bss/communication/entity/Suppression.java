package com.bss.communication.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** An address the tenant's ESP told us to stop emailing (hard bounce,
 * complaint, unsubscribe). The in-app inbox is unaffected — email just
 * stops knocking on a door that bounced. */
@Entity
@Table(name = "suppression")
public class Suppression {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
