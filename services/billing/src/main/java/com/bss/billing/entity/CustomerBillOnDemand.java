package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF678 CustomerBillOnDemand: a stored on-demand bill request. */
@Entity
@Table(name = "customer_bill_on_demand")
public class CustomerBillOnDemand {

    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "href")
    private String href;
    @Column(name = "tenant_id")
    private String tenantId;
    @Column(name = "state")
    private String state;
    @Column(name = "payload_json", length = 8000)
    private String payloadJson;
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
