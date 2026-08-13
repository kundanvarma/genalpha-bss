package com.bss.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * TMF633 Service Catalog — a ServiceSpecification. The SID split the retail
 * catalog never modelled: a Customer-Facing Service (CFS — the access the buyer
 * consumes, with layer/bandwidth characteristics) realised over a Resource-Facing
 * Service (RFS — the owner's port/bitstream), a ProductOffering referencing the CFS.
 * {@code serviceType} is a convenience tag (CFS | RFS); the real CFS→RFS link lives
 * in {@code serviceSpecRelationship}.
 */
@Entity
@Table(name = "service_specification")
public class ServiceSpecification {

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

    @Column(name = "is_bundle")
    private Boolean isBundle;

    /** Convenience tag: CFS (customer-facing) or RFS (resource-facing). */
    @Column(name = "service_type", length = 16)
    private String serviceType;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** JSON array of TMF633 serviceSpecCharacteristic (accessLayer, downloadSpeed, ...), verbatim. */
    @Column(name = "service_spec_characteristic", length = 4000)
    private String serviceSpecCharacteristicJson;

    /** JSON array of TMF633 serviceSpecRelationship (a CFS reliesOn its RFS), verbatim. */
    @Column(name = "service_spec_relationship", length = 4000)
    private String serviceSpecRelationshipJson;

    public ServiceSpecification() {
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

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getServiceSpecCharacteristicJson() {
        return serviceSpecCharacteristicJson;
    }

    public void setServiceSpecCharacteristicJson(String serviceSpecCharacteristicJson) {
        this.serviceSpecCharacteristicJson = serviceSpecCharacteristicJson;
    }

    public String getServiceSpecRelationshipJson() {
        return serviceSpecRelationshipJson;
    }

    public void setServiceSpecRelationshipJson(String serviceSpecRelationshipJson) {
        this.serviceSpecRelationshipJson = serviceSpecRelationshipJson;
    }
}
