package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "quote")
public class Quote {

    public static final String IN_PROGRESS = "inProgress";
    public static final String APPROVED = "approved";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "intent_id", length = 36)
    private String intentId;

    @Column(name = "owner_party_id", length = 64)
    private String ownerPartyId;

    @Column(name = "items", nullable = false, length = 6000)
    private String items;

    @Column(name = "monthly_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyTotal;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "narrative", length = 3000)
    private String narrative;

    @Column(name = "product_order_id", length = 36)
    private String productOrderId;

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
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public BigDecimal getMonthlyTotal() { return monthlyTotal; }
    public void setMonthlyTotal(BigDecimal monthlyTotal) { this.monthlyTotal = monthlyTotal; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNarrative() { return narrative; }
    public void setNarrative(String narrative) { this.narrative = narrative; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String productOrderId) { this.productOrderId = productOrderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
