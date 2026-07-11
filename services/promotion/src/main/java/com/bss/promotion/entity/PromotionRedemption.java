package com.bss.promotion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One customer's claim on a promotion, consumed by the billing run. */
@Entity
@Table(name = "promotion_redemption")
public class PromotionRedemption {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "promotion_id", nullable = false, length = 36)
    private String promotionId;

    @Column(name = "promotion_name", nullable = false)
    private String promotionName;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "owner_party_id", nullable = false, length = 64)
    private String ownerPartyId;

    @Column(name = "percentage", nullable = false)
    private BigDecimal percentage;

    @Column(name = "applies_to", length = 2000)
    private String appliesToJson;

    @Column(name = "months_left")
    private Integer monthsLeft;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPromotionId() { return promotionId; }
    public void setPromotionId(String promotionId) { this.promotionId = promotionId; }
    public String getPromotionName() { return promotionName; }
    public void setPromotionName(String promotionName) { this.promotionName = promotionName; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }
    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
    public String getAppliesToJson() { return appliesToJson; }
    public void setAppliesToJson(String appliesToJson) { this.appliesToJson = appliesToJson; }
    public Integer getMonthsLeft() { return monthsLeft; }
    public void setMonthsLeft(Integer monthsLeft) { this.monthsLeft = monthsLeft; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
