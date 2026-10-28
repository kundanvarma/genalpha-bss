package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One partner activation that is not simply done (#355): waiting on the partner,
 * refused by it, or ended here but not yet confirmed ended there. The request key
 * the partner dedupes on is the service id. The sweep settles these rows; an
 * activation the partner confirmed at once never needs one.
 */
@Entity
@Table(name = "partner_activation")
public class PartnerActivation {

    public static final String PENDING = "pending";
    public static final String ACTIVE = "active";
    public static final String REFUSED = "refused";
    public static final String DEACTIVATION_PENDING = "deactivation-pending";
    public static final String DEACTIVATED = "deactivated";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "service_id", nullable = false, length = 36)
    private String serviceId;

    @Column(name = "product_order_id", length = 64)
    private String productOrderId;

    @Column(name = "order_item_id", length = 64)
    private String orderItemId;

    @Column(name = "offering_name", nullable = false, length = 255)
    private String offeringName;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "state", nullable = false, length = 24)
    private String state;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private java.time.OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private java.time.OffsetDateTime lastUpdate;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getProductOrderId() {
        return productOrderId;
    }

    public void setProductOrderId(String productOrderId) {
        this.productOrderId = productOrderId;
    }

    public String getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(String orderItemId) {
        this.orderItemId = orderItemId;
    }

    public String getOfferingName() {
        return offeringName;
    }

    public void setOfferingName(String offeringName) {
        this.offeringName = offeringName;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public java.time.OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public java.time.OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(java.time.OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
