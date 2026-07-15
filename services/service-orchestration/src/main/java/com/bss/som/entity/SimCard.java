package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The SIM behind a numbered service, minted at activation alongside the
 * MSISDN. The PUK is operator-side card data (revealable to the owner);
 * the PIN lives on the card and changes through the SIM-platform seam —
 * it is never stored here.
 */
@Entity
@Table(name = "sim_card")
public class SimCard {

    @Id
    private String iccid;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "service_id")
    private String serviceId;

    private String puk;

    /** active | blocked (lost/stolen) | replaced (upgrade/damaged). */
    private String status = "active";

    @Column(name = "replaced_reason", length = 32)
    private String replacedReason;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getIccid() { return iccid; }
    public void setIccid(String iccid) { this.iccid = iccid; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getPuk() { return puk; }
    public void setPuk(String puk) { this.puk = puk; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReplacedReason() { return replacedReason; }
    public void setReplacedReason(String v) { this.replacedReason = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
