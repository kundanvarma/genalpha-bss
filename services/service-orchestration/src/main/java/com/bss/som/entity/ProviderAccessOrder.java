package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The PROVIDER side of open access: an access-seeker order a RETAILER placed with
 * us, the fibre owner, over our MEF LSO Sonata face. The mirror of a
 * WholesaleAccessOrder (which is what WE buy upstream) — this is what we SELL. We
 * provision it and notify the retailer's callback when it goes live.
 */
@Entity
@Table(name = "provider_access_order")
public class ProviderAccessOrder {

    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String IN_PROGRESS = "inProgress";
    public static final String ACTIVE = "active";
    public static final String FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** The retailer's own order reference (their WholesaleAccessOrder id). */
    @Column(name = "buyer_ref", length = 64)
    private String buyerRef;

    /** The retailer party we sell to (for the wholesale bill). */
    @Column(name = "retailer_party_id", length = 64)
    private String retailerPartyId;

    /** Where we notify the retailer when the line is live. */
    @Column(name = "callback_url", length = 512)
    private String callbackUrl;

    @Column(name = "access_layer", length = 32)
    private String accessLayer;

    @Column(name = "bandwidth_mbps")
    private Integer bandwidthMbps;

    @Column(name = "post_code", length = 16)
    private String postCode;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** When the async provisioning is due to complete. */
    @Column(name = "activate_at")
    private OffsetDateTime activateAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getBuyerRef() { return buyerRef; }
    public void setBuyerRef(String buyerRef) { this.buyerRef = buyerRef; }
    public String getRetailerPartyId() { return retailerPartyId; }
    public void setRetailerPartyId(String retailerPartyId) { this.retailerPartyId = retailerPartyId; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getAccessLayer() { return accessLayer; }
    public void setAccessLayer(String accessLayer) { this.accessLayer = accessLayer; }
    public Integer getBandwidthMbps() { return bandwidthMbps; }
    public void setBandwidthMbps(Integer bandwidthMbps) { this.bandwidthMbps = bandwidthMbps; }
    public String getPostCode() { return postCode; }
    public void setPostCode(String postCode) { this.postCode = postCode; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getActivateAt() { return activateAt; }
    public void setActivateAt(OffsetDateTime activateAt) { this.activateAt = activateAt; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
