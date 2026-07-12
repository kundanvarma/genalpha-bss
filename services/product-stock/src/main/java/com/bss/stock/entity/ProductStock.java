package com.bss.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "product_stock")
public class ProductStock {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name")
    private String name;

    /** JSON object referencing the stocked product offering, echoed verbatim. */
    @Column(name = "product_offering", length = 4000)
    private String productOfferingJson;

    /** Denormalised offering id: reservation lookups and TMF630 filtering. */
    @Column(name = "product_offering_id", length = 36)
    private String productOfferingId;

    @Column(name = "stocked_amount", nullable = false)
    private Integer stockedAmount;

    @Column(name = "stocked_units", length = 64)
    private String stockedUnits;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    @Column(name = "payload_json", length = 8000)
    private String payloadJson;

    /** The tenant this row belongs to; never exposed in API responses. */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public ProductStock() {
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

    public String getProductOfferingJson() {
        return productOfferingJson;
    }

    public void setProductOfferingJson(String productOfferingJson) {
        this.productOfferingJson = productOfferingJson;
    }

    public String getProductOfferingId() {
        return productOfferingId;
    }

    public void setProductOfferingId(String productOfferingId) {
        this.productOfferingId = productOfferingId;
    }

    public Integer getStockedAmount() {
        return stockedAmount;
    }

    public void setStockedAmount(Integer stockedAmount) {
        this.stockedAmount = stockedAmount;
    }

    public String getStockedUnits() {
        return stockedUnits;
    }

    public void setStockedUnits(String stockedUnits) {
        this.stockedUnits = stockedUnits;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
