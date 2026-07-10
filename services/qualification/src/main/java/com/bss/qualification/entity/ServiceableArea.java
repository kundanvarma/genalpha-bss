package com.bss.qualification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "serviceable_area")
public class ServiceableArea {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "product_offering")
    private String productOfferingJson;

    @Column(name = "product_offering_id")
    private String productOfferingId;

    @Column(name = "postcode_prefix")
    private String postcodePrefix;

    @Column(name = "name")
    private String name;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** The tenant this row belongs to; never exposed in API responses. */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public ServiceableArea() {
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

    public String getPostcodePrefix() {
        return postcodePrefix;
    }

    public void setPostcodePrefix(String postcodePrefix) {
        this.postcodePrefix = postcodePrefix;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
