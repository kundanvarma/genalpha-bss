package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One joiner redeeming one code: pending until their first completed
 *  order, then rewarded — or held when the velocity guard smells abuse. */
@Entity
@Table(name = "referral_conversion")
public class ReferralConversion {

    public static final String PENDING = "pending";
    public static final String REWARDED = "rewarded";
    public static final String HELD = "held";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "referrer_party_id", nullable = false, length = 64)
    private String referrerPartyId;

    @Column(name = "joiner_party_id", nullable = false, length = 64)
    private String joinerPartyId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reward_gb", precision = 6, scale = 2)
    private BigDecimal rewardGb;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "rewarded_at")
    private OffsetDateTime rewardedAt;

    /** G3: the area this signup grows (the street's unlock game). */
    @Column(name = "area_code", length = 32)
    private String areaCode;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getReferrerPartyId() { return referrerPartyId; }
    public void setReferrerPartyId(String v) { this.referrerPartyId = v; }
    public String getJoinerPartyId() { return joinerPartyId; }
    public void setJoinerPartyId(String v) { this.joinerPartyId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public BigDecimal getRewardGb() { return rewardGb; }
    public void setRewardGb(BigDecimal v) { this.rewardGb = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getRewardedAt() { return rewardedAt; }
    public void setRewardedAt(OffsetDateTime v) { this.rewardedAt = v; }
    public String getAreaCode() { return areaCode; }
    public void setAreaCode(String v) { this.areaCode = v; }
}
