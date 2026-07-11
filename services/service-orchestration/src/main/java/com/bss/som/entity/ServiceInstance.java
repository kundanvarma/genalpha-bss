package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF638: a running service, born from an activated service order. */
@Entity
@Table(name = "service")
public class ServiceInstance {

    public static final String ACTIVE = "active";
    public static final String TERMINATED = "terminated";

    @jakarta.persistence.Column(name = "delivery_path", length = 128)
    private String deliveryPath;

    public String getDeliveryPath() { return deliveryPath; }
    public void setDeliveryPath(String deliveryPath) { this.deliveryPath = deliveryPath; }

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "service_order_id", nullable = false, length = 36)
    private String serviceOrderId;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getServiceOrderId() { return serviceOrderId; }
    public void setServiceOrderId(String serviceOrderId) { this.serviceOrderId = serviceOrderId; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
