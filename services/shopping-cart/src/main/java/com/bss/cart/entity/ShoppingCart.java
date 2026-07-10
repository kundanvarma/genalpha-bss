package com.bss.cart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "shopping_cart")
public class ShoppingCart {

    public static final String ACTIVE = "active";
    public static final String CHECKED_OUT = "checkedOut";
    public static final String ABANDONED = "abandoned";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "status")
    private String status;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "cart_item")
    private String cartItemJson;

    @Column(name = "related_entity")
    private String relatedEntityJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public ShoppingCart() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getCartItemJson() {
        return cartItemJson;
    }

    public void setCartItemJson(String cartItemJson) {
        this.cartItemJson = cartItemJson;
    }

    public String getRelatedEntityJson() {
        return relatedEntityJson;
    }

    public void setRelatedEntityJson(String relatedEntityJson) {
        this.relatedEntityJson = relatedEntityJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
