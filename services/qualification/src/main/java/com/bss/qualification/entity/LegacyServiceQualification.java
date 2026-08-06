package com.bss.qualification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A v3-era qualification task: the posted document, evaluated and kept. */
@Entity
@Table(name = "legacy_service_qualification")
public class LegacyServiceQualification {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "external_id")
    private String externalId;

    private String state;

    @Column(name = "document_json")
    private String documentJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String documentJson) { this.documentJson = documentJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
