package com.bss.fulfilment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF700: the parcel, with states the warehouse drives. */
@Entity
@Table(name = "shipping_order")
public class ShippingOrder {

    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String IN_PROGRESS = "inProgress";
    public static final String SHIPPED = "shipped";
    public static final String DELIVERED = "delivered";
    public static final String CANCELLED = "cancelled";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    private String state;

    @Column(name = "items_json")
    private String itemsJson;

    @Column(name = "place_json")
    private String placeJson;

    @Column(name = "tracking_ref")
    private String trackingRef;

    /** The carrier that booked the parcel (from the logistics seam) — data, not a
     * constant, so the shop shows whoever actually shipped it. */
    @Column(name = "carrier")
    private String carrier;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String v) { this.productOrderId = v; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String v) { this.ownerPartyId = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String v) { this.itemsJson = v; }
    public String getPlaceJson() { return placeJson; }
    public void setPlaceJson(String v) { this.placeJson = v; }
    public String getTrackingRef() { return trackingRef; }
    public void setTrackingRef(String v) { this.trackingRef = v; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String v) { this.carrier = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
