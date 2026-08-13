package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The access-seeker order we place UPSTREAM to a third-party fibre owner (open
 * access): "activate wholesale access for this retail line, at this address, at
 * this layer". It is the wholesale mirror of a ServiceOrder — a resource with its
 * own lifecycle — carrying the owner, the access layer (L2 VULA / L3 activated)
 * and the owner OSS's own reference (externalId). MEF LSO Sonata is the wire shape
 * a real adapter would target; dev activates it through a mock.
 */
@Entity
@Table(name = "wholesale_access_order")
public class WholesaleAccessOrder {

    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String IN_PROGRESS = "inProgress";
    public static final String ACTIVE = "active";
    public static final String FAILED = "failed";
    public static final String CEASED = "ceased";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "product_order_id", length = 36)
    private String productOrderId;

    /** The retail service instance this wholesale access realizes. */
    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "access_owner", nullable = false, length = 64)
    private String accessOwner;

    @Column(name = "access_layer", length = 32)
    private String accessLayer;

    @Column(name = "bandwidth_mbps")
    private Integer bandwidthMbps;

    @Column(name = "post_code", length = 16)
    private String postCode;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    /** The owner OSS's own order reference (a Sonata order id in production). */
    @Column(name = "external_id", length = 64)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String productOrderId) { this.productOrderId = productOrderId; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public String getAccessOwner() { return accessOwner; }
    public void setAccessOwner(String accessOwner) { this.accessOwner = accessOwner; }
    public String getAccessLayer() { return accessLayer; }
    public void setAccessLayer(String accessLayer) { this.accessLayer = accessLayer; }
    public Integer getBandwidthMbps() { return bandwidthMbps; }
    public void setBandwidthMbps(Integer bandwidthMbps) { this.bandwidthMbps = bandwidthMbps; }
    public String getPostCode() { return postCode; }
    public void setPostCode(String postCode) { this.postCode = postCode; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
