package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One delta-export run to the number-directory partner (audit header). */
@Entity
@Table(name = "directory_export_run")
public class DirectoryExportRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "ran_at", nullable = false)
    private OffsetDateTime ranAt;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public OffsetDateTime getRanAt() { return ranAt; }
    public void setRanAt(OffsetDateTime ranAt) { this.ranAt = ranAt; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
}
