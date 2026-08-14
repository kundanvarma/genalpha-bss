package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A saved, named audience: a reusable target defined by a CRITERIA TREE rather
 * than a single segment string. The tree composes predicates over the signals
 * this profile legitimately holds (browsed interests, analytics audiences) with
 * all / any / not — so a marketer edits "returning device-shoppers who are NOT
 * already churn-risk" once and campaigns reference it by id.
 */
@Entity
@Table(name = "audience")
public class Audience {

    @Id
    private String id;
    private String href;

    @Column(name = "tenant_id")
    private String tenantId;

    private String name;

    /** JSON criteria tree: {all|any:[..]} | {not:{..}} | {type,value} leaf. */
    @Column(length = 8000)
    private String criteria;

    /** customer (default) | prospect — which population the tree resolves over. */
    @Column(name = "population")
    private String population;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHref() { return href; }
    public void setHref(String href) { this.href = href; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCriteria() { return criteria; }
    public void setCriteria(String criteria) { this.criteria = criteria; }
    public String getPopulation() { return population; }
    public void setPopulation(String population) { this.population = population; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
