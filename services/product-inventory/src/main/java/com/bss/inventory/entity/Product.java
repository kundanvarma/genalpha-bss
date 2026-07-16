package com.bss.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status")
    private String status;

    /** JSON object referencing the catalog offering, echoed verbatim. */
    @Column(name = "product_offering", length = 4000)
    private String productOfferingJson;

    /** JSON object referencing the billing account, echoed verbatim. */
    @Column(name = "billing_account", length = 4000)
    private String billingAccountJson;

    @Column(name = "product_characteristic", length = 4000)
    private String productCharacteristicJson;

    @Column(name = "product_price", length = 4000)
    private String productPriceJson;

    /** Mid-cycle plan change: what this product WAS, and when it switched
     * (billing prorates each plan for its own days). */
    @Column(name = "previous_offering", length = 4000)
    private String previousOfferingJson;

    @Column(name = "offering_changed_at")
    private java.time.OffsetDateTime offeringChangedAt;

    @Column(name = "related_party", length = 4000)
    private String relatedPartyJson;

    /** The customer party this product belongs to; drives channel scoping. */
    @Column(name = "owner_party_id", length = 36)
    private String ownerPartyId;

    /** The tenant this row belongs to; never exposed in API responses. */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public Product() {
    }

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

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProductOfferingJson() {
        return productOfferingJson;
    }

    public void setProductOfferingJson(String productOfferingJson) {
        this.productOfferingJson = productOfferingJson;
    }

    public String getBillingAccountJson() {
        return billingAccountJson;
    }

    public void setBillingAccountJson(String billingAccountJson) {
        this.billingAccountJson = billingAccountJson;
    }

    public String getProductCharacteristicJson() {
        return productCharacteristicJson;
    }

    public void setProductCharacteristicJson(String productCharacteristicJson) {
        this.productCharacteristicJson = productCharacteristicJson;
    }

    public String getProductPriceJson() {
        return productPriceJson;
    }

    public void setProductPriceJson(String productPriceJson) {
        this.productPriceJson = productPriceJson;
    }

    public String getRelatedPartyJson() {
        return relatedPartyJson;
    }

    public void setRelatedPartyJson(String relatedPartyJson) {
        this.relatedPartyJson = relatedPartyJson;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getPreviousOfferingJson() { return previousOfferingJson; }
    public void setPreviousOfferingJson(String v) { this.previousOfferingJson = v; }
    public java.time.OffsetDateTime getOfferingChangedAt() { return offeringChangedAt; }
    public void setOfferingChangedAt(java.time.OffsetDateTime v) { this.offeringChangedAt = v; }
}
