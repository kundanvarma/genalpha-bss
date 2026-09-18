package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * TMF640 monitor: the state of one activation request. Activation through
 * this face completes synchronously, so a monitor is born "Completed" with
 * the request and the response it produced — the record a caller polls
 * when an activation is asynchronous elsewhere.
 */
@Entity
@Table(name = "service_monitor")
public class ServiceMonitor {

    public static final String COMPLETED = "Completed";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "service_id", nullable = false, length = 36)
    private String serviceId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "source_href")
    private String sourceHref;

    @Column(name = "request_json", length = 16000)
    private String requestJson;

    @Column(name = "response_json", length = 16000)
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSourceHref() { return sourceHref; }
    public void setSourceHref(String sourceHref) { this.sourceHref = sourceHref; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
