package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Per-tenant cursor into the national registry's sequential event feed —
 * the one piece of state the re-sync worker keeps between polls.
 */
@Entity
@Table(name = "registry_feed_cursor")
public class RegistryFeedCursor {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
