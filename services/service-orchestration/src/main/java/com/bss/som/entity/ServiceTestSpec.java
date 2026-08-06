package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A TMF653 test specification: what a test IS, as tenant data. */
@Entity
@Table(name = "service_test_spec")
public class ServiceTestSpec {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String name;

    @Column(name = "related_spec_json")
    private String relatedSpecJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRelatedSpecJson() { return relatedSpecJson; }
    public void setRelatedSpecJson(String relatedSpecJson) { this.relatedSpecJson = relatedSpecJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
