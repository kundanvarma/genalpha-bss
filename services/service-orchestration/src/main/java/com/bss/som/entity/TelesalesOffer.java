package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The call's HONEST output: an offer, never an order. The order is
 * born only when the customer's written confirmation comes back —
 * because until then, legally, there is no agreement. */
@Entity
@Table(name = "telesales_offer")
public class TelesalesOffer {

    public static final String OFFERED = "offered";
    public static final String CONFIRMED = "confirmed";
    public static final String EXPIRED = "expired";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "dealer_org_id", nullable = false)
    private String dealerOrgId;

    private String store;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "offering_id", nullable = false)
    private String offeringId;

    @Column(name = "offering_name")
    private String offeringName;

    @Column(name = "confirm_token", nullable = false)
    private String confirmToken;

    @Column(nullable = false)
    private String status;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getDealerOrgId() { return dealerOrgId; }
    public void setDealerOrgId(String v) { this.dealerOrgId = v; }
    public String getStore() { return store; }
    public void setStore(String v) { this.store = v; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String v) { this.customerId = v; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String v) { this.customerPhone = v; }
    public String getOfferingId() { return offeringId; }
    public void setOfferingId(String v) { this.offeringId = v; }
    public String getOfferingName() { return offeringName; }
    public void setOfferingName(String v) { this.offeringName = v; }
    public String getConfirmToken() { return confirmToken; }
    public void setConfirmToken(String v) { this.confirmToken = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String v) { this.productOrderId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime v) { this.confirmedAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
