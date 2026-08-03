package com.bss.address.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF674: a named, reusable place — a party's premises with a status and a
 * stored address. The branch entered once, referenced forever. */
@Entity
@Table(name = "geographic_site")
public class GeographicSite {

    public static final String PLANNED = "planned";
    public static final String ACTIVE = "active";
    public static final String RETIRED = "retired";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "related_party", length = 2000)
    private String relatedPartyJson;

    @Column(name = "address_id", length = 36)
    private String addressId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public GeographicSite() {
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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRelatedPartyJson() {
        return relatedPartyJson;
    }

    public void setRelatedPartyJson(String relatedPartyJson) {
        this.relatedPartyJson = relatedPartyJson;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
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
