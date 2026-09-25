package com.bss.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * TMF634 Resource Catalog — a ResourceSpecification: the kind of resource a
 * resource-facing service realises (a number from a pool, a SIM or eSIM
 * profile, an online-charging subscriber, a slice binding, a wholesale access,
 * a partner entitlement, a customer-premises device). It names the SEAM an
 * adapter provides, never the vendor — the tenant's configuration chooses the
 * vendor behind each seam. Served by the product-catalog component beside
 * TMF633 (ADR-0021); the third catalog layer of the SID split.
 */
@Entity
@Table(name = "resource_specification")
public class ResourceSpecification {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "version")
    private String version;

    @Column(name = "lifecycle_status")
    private String lifecycleStatus;

    /** TMF634 category: a free classifier ("network", "logical", "physical", or the house seam family). */
    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "is_bundle")
    private Boolean isBundle;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** JSON array of TMF634 resourceSpecCharacteristic (seam, ...), verbatim. */
    @Column(name = "resource_spec_characteristic", length = 4000)
    private String resourceSpecCharacteristicJson;

    /** JSON array of TMF634 resourceSpecRelationship, verbatim. */
    @Column(name = "resource_spec_relationship", length = 4000)
    private String resourceSpecRelationshipJson;

    public ResourceSpecification() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getResourceSpecCharacteristicJson() {
        return resourceSpecCharacteristicJson;
    }

    public void setResourceSpecCharacteristicJson(String resourceSpecCharacteristicJson) {
        this.resourceSpecCharacteristicJson = resourceSpecCharacteristicJson;
    }

    public String getResourceSpecRelationshipJson() {
        return resourceSpecRelationshipJson;
    }

    public void setResourceSpecRelationshipJson(String resourceSpecRelationshipJson) {
        this.resourceSpecRelationshipJson = resourceSpecRelationshipJson;
    }
}
