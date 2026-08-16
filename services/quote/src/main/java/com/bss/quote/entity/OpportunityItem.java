package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A line on the deal: one product offering (from the TMF620 catalog),
 *  a quantity and a unit price. The opportunity's amount is the sum of
 *  these when any exist. */
@Entity
@Table(name = "opportunity_item")
public class OpportunityItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "opportunity_id", nullable = false, length = 36)
    private String opportunityId;

    @Column(name = "offering_id", length = 64)
    private String offeringId;

    @Column(name = "offering_name", nullable = false)
    private String offeringName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "currency", length = 3)
    private String currency;

    /** A recurring (monthly) charge vs a one-time charge — splits the quote
     *  total into MRR and one-off. */
    @Column(name = "recurring", nullable = false)
    private boolean recurring = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getOpportunityId() { return opportunityId; }
    public void setOpportunityId(String v) { this.opportunityId = v; }
    public String getOfferingId() { return offeringId; }
    public void setOfferingId(String v) { this.offeringId = v; }
    public String getOfferingName() { return offeringName; }
    public void setOfferingName(String v) { this.offeringName = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal v) { this.unitPrice = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public boolean isRecurring() { return recurring; }
    public void setRecurring(boolean v) { this.recurring = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
