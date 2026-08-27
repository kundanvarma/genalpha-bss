package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One JSON row actually shipped to the directory partner. What is NOT here
 * is the point: reserved, secret-number and protected-address parties never
 * produce a row — the regulator audits leaks of reserved numbers.
 */
@Entity
@Table(name = "directory_export_row")
public class DirectoryExportRow {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "party_id", nullable = false, length = 36)
    private String partyId;

    @Column(name = "service_ref", length = 64)
    private String serviceRef;

    @Column(name = "payload", nullable = false, length = 2000)
    private String payload;

    @Column(name = "exported_at", nullable = false)
    private OffsetDateTime exportedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getServiceRef() { return serviceRef; }
    public void setServiceRef(String serviceRef) { this.serviceRef = serviceRef; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public OffsetDateTime getExportedAt() { return exportedAt; }
    public void setExportedAt(OffsetDateTime exportedAt) { this.exportedAt = exportedAt; }
}
