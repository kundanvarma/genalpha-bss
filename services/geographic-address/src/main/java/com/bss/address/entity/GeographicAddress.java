package com.bss.address.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "geographic_address")
public class GeographicAddress {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "street1")
    private String street1;

    @Column(name = "street2")
    private String street2;

    @Column(name = "post_code", length = 32)
    private String postCode;

    @Column(name = "city", length = 128)
    private String city;

    @Column(name = "state_or_province", length = 128)
    private String stateOrProvince;

    @Column(name = "country", length = 64)
    private String country;

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
    public String getStreet1() { return street1; }
    public void setStreet1(String street1) { this.street1 = street1; }
    public String getStreet2() { return street2; }
    public void setStreet2(String street2) { this.street2 = street2; }
    public String getPostCode() { return postCode; }
    public void setPostCode(String postCode) { this.postCode = postCode; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getStateOrProvince() { return stateOrProvince; }
    public void setStateOrProvince(String stateOrProvince) { this.stateOrProvince = stateOrProvince; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
