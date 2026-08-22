package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** An area's unlock goal — the street's game, with a public score. */
@Entity
@Table(name = "community_goal")
public class CommunityGoal {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "area_code", nullable = false, length = 32)
    private String areaCode;

    @Column(name = "target", nullable = false)
    private int target;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getAreaCode() { return areaCode; }
    public void setAreaCode(String v) { this.areaCode = v; }
    public int getTarget() { return target; }
    public void setTarget(int v) { this.target = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
