package com.bss.porting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "porting_order")
public class PortingOrder {

    public static final String PORT_IN = "portIn";
    public static final String PORT_OUT = "portOut";

    public static final String REQUESTED = "requested";
    public static final String VALIDATED = "validated";
    public static final String SCHEDULED = "scheduled";
    public static final String COMPLETED = "completed";
    public static final String REJECTED = "rejected";
    public static final String CANCELLED = "cancelled";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "other_operator", length = 128)
    private String otherOperator;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "product_order_id", length = 36)
    private String productOrderId;

    @Column(name = "gateway", nullable = false, length = 32)
    private String gateway;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "requested_cutover")
    private OffsetDateTime requestedCutover;

    @Column(name = "scheduled_cutover")
    private OffsetDateTime scheduledCutover;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

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
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getOtherOperator() { return otherOperator; }
    public void setOtherOperator(String otherOperator) { this.otherOperator = otherOperator; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String productOrderId) { this.productOrderId = productOrderId; }
    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public OffsetDateTime getRequestedCutover() { return requestedCutover; }
    public void setRequestedCutover(OffsetDateTime v) { this.requestedCutover = v; }
    public OffsetDateTime getScheduledCutover() { return scheduledCutover; }
    public void setScheduledCutover(OffsetDateTime v) { this.scheduledCutover = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
