package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The audit that outlives the erased: who, when, and the full report. */
@Entity
@Table(name = "erasure_record")
public class ErasureRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "party_id", nullable = false, length = 64)
    private String partyId;

    @Column(name = "executed_by", nullable = false, length = 64)
    private String executedBy;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    @Column(name = "report_json", nullable = false, columnDefinition = "text")
    private String reportJson;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public void setExecutedBy(String executedBy) {
        this.executedBy = executedBy;
    }

    public OffsetDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(OffsetDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }
}
