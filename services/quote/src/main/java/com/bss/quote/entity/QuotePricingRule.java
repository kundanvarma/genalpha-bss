package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A volume pricing tier: at least min_quantity of an offering earns a line
 *  discount (optionally scoped to a customer segment). */
@Entity
@Table(name = "quote_pricing_rule")
public class QuotePricingRule {
    @Id @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "offering_name", nullable = false, length = 255) private String offeringName;
    @Column(name = "min_quantity", nullable = false) private int minQuantity;
    @Column(name = "segment", length = 64) private String segment;
    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2) private BigDecimal discountPercent;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;

    public String getId() { return id; } public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { this.tenantId = v; }
    public String getOfferingName() { return offeringName; } public void setOfferingName(String v) { this.offeringName = v; }
    public int getMinQuantity() { return minQuantity; } public void setMinQuantity(int v) { this.minQuantity = v; }
    public String getSegment() { return segment; } public void setSegment(String v) { this.segment = v; }
    public BigDecimal getDiscountPercent() { return discountPercent; } public void setDiscountPercent(BigDecimal v) { this.discountPercent = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
