package com.bss.qualification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A v3-era product-offering qualification task: posted, evaluated, kept. */
@Entity
@Table(name = "legacy_poq")
public class LegacyPoq {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String state;

    @Column(name = "document_json")
    private String documentJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String documentJson) { this.documentJson = documentJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
