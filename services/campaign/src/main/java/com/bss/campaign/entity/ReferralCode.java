package com.bss.campaign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;

/** A customer's shareable code — one per referrer, forever theirs. */
@Entity
@Table(name = "referral_code")
@IdClass(ReferralCode.Key.class)
public class ReferralCode {

    public static class Key implements Serializable {
        public String tenantId;
        public String code;
        public Key() { }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && tenantId.equals(k.tenantId) && code.equals(k.code);
        }
        @Override public int hashCode() { return (tenantId + code).hashCode(); }
    }

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "referrer_party_id", nullable = false, length = 64)
    private String referrerPartyId;

    @Column(name = "club_org_id", length = 64)
    private String clubOrgId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getReferrerPartyId() { return referrerPartyId; }
    public void setReferrerPartyId(String v) { this.referrerPartyId = v; }
    public String getClubOrgId() { return clubOrgId; }
    public void setClubOrgId(String v) { this.clubOrgId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
