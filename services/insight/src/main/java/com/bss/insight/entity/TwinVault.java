package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** What makes a twin reversible — the per-signal key and the offset map.
 * Destroying this row makes the twin permanently unlinkable fiction. */
@Entity
@Table(name = "twin_vault")
public class TwinVault {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "signal_id", nullable = false)
    private String signalId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "twin_key", nullable = false)
    private String twinKey;

    @Column(name = "offset_map", length = 4000)
    private String offsetMap;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSignalId() { return signalId; }
    public void setSignalId(String v) { this.signalId = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getTwinKey() { return twinKey; }
    public void setTwinKey(String v) { this.twinKey = v; }
    public String getOffsetMap() { return offsetMap; }
    public void setOffsetMap(String v) { this.offsetMap = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
