package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One behavioral breadcrumb — stored ONLY after analytics consent. */
@Entity
@Table(name = "visitor_event")
public class VisitorEvent {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "visitor_id")
    private String visitorId;

    private String type;
    private String category;

    @Column(name = "offering_id")
    private String offeringId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getVisitorId() { return visitorId; }
    public void setVisitorId(String visitorId) { this.visitorId = visitorId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getOfferingId() { return offeringId; }
    public void setOfferingId(String offeringId) { this.offeringId = offeringId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
