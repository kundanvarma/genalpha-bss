package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * What the TMF640 activation side adds to a service row: the caller's own
 * document (specification ref, characteristics, places, parties — echoed
 * back verbatim) and the activation date. One row per service that came in
 * through the activation face; services the orchestrator stood up have none.
 */
@Entity
@Table(name = "service_activation")
public class ServiceActivation {

    @Id
    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "service_date", nullable = false)
    private OffsetDateTime serviceDate;

    @Column(name = "document_json", length = 16000)
    private String documentJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public OffsetDateTime getServiceDate() { return serviceDate; }
    public void setServiceDate(OffsetDateTime serviceDate) { this.serviceDate = serviceDate; }
    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String documentJson) { this.documentJson = documentJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
