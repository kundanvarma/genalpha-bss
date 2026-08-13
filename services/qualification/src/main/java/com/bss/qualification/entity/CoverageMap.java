package com.bss.qualification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One stroke of the operator's footprint: this access technology reaches
 * postcodes with this prefix, at up to this bandwidth. An EMPTY prefix
 * matches everywhere — the mobile fallback. Data, never code: coverage
 * grows by POSTing rows, not by redeploying.
 */
@Entity
@Table(name = "coverage_map")
public class CoverageMap {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "technology", nullable = false, length = 64)
    private String technology;

    @Column(name = "postcode_prefix", nullable = false, length = 16)
    private String postcodePrefix;

    @Column(name = "max_down_mbps", nullable = false)
    private Integer maxDownMbps;

    @Column(name = "max_up_mbps")
    private Integer maxUpMbps;

    @Column(name = "note")
    private String note;

    /** Open access: WHO owns the fibre this row represents. Null = our own
     *  network (or legacy direct). A value names the wholesale access owner. */
    @Column(name = "access_owner", length = 64)
    private String accessOwner;

    /** The access LAYER an owner sells: L2-VULA (we run our own IP) or
     *  L3-activated (we resell). Null for our own network. */
    @Column(name = "access_layer", length = 32)
    private String accessLayer;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** The tenant this row belongs to; never exposed in API responses. */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public CoverageMap() {
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

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public String getPostcodePrefix() {
        return postcodePrefix;
    }

    public void setPostcodePrefix(String postcodePrefix) {
        this.postcodePrefix = postcodePrefix;
    }

    public Integer getMaxDownMbps() {
        return maxDownMbps;
    }

    public void setMaxDownMbps(Integer maxDownMbps) {
        this.maxDownMbps = maxDownMbps;
    }

    public String getAccessOwner() {
        return accessOwner;
    }

    public void setAccessOwner(String accessOwner) {
        this.accessOwner = accessOwner;
    }

    public String getAccessLayer() {
        return accessLayer;
    }

    public void setAccessLayer(String accessLayer) {
        this.accessLayer = accessLayer;
    }

    public Integer getMaxUpMbps() {
        return maxUpMbps;
    }

    public void setMaxUpMbps(Integer maxUpMbps) {
        this.maxUpMbps = maxUpMbps;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
