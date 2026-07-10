package com.bss.ordering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "product_order")
public class ProductOrder {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "category")
    private String category;

    @Column(name = "product_offering_id")
    private String productOfferingId;

    @Column(name = "billing_account_id")
    private String billingAccountId;

    /** JSON array of order items, stored verbatim (TMF622 mandatory). */
    @Column(name = "product_order_item", length = 4000)
    private String productOrderItemJson;

    /** JSON array of related parties, stored verbatim. */
    @Column(name = "related_party", length = 4000)
    private String relatedPartyJson;

    /** The customer party this order belongs to; drives channel scoping. */
    @Column(name = "owner_party_id", length = 36)
    private String ownerPartyId;

    /** JSON array of payment references, stored verbatim. */
    @Column(name = "payment", length = 2000)
    private String paymentJson;

    @Column(name = "order_date")
    private OffsetDateTime orderDate;

    public ProductOrder() {
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getProductOfferingId() {
        return productOfferingId;
    }

    public void setProductOfferingId(String productOfferingId) {
        this.productOfferingId = productOfferingId;
    }

    public String getBillingAccountId() {
        return billingAccountId;
    }

    public void setBillingAccountId(String billingAccountId) {
        this.billingAccountId = billingAccountId;
    }

    public String getProductOrderItemJson() {
        return productOrderItemJson;
    }

    public void setProductOrderItemJson(String productOrderItemJson) {
        this.productOrderItemJson = productOrderItemJson;
    }

    public String getRelatedPartyJson() {
        return relatedPartyJson;
    }

    public void setRelatedPartyJson(String relatedPartyJson) {
        this.relatedPartyJson = relatedPartyJson;
    }

    public OffsetDateTime getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(OffsetDateTime orderDate) {
        this.orderDate = orderDate;
    }

    public String getOwnerPartyId() {
        return ownerPartyId;
    }

    public void setOwnerPartyId(String ownerPartyId) {
        this.ownerPartyId = ownerPartyId;
    }

    public String getPaymentJson() {
        return paymentJson;
    }

    public void setPaymentJson(String paymentJson) {
        this.paymentJson = paymentJson;
    }
}
