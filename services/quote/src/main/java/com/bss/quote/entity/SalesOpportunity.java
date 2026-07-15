package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF699 salesOpportunity: the revenue conversation a qualified lead
 * became — developed until it is won (ideally with a quote ref) or lost. */
@Entity
@Table(name = "sales_opportunity")
public class SalesOpportunity {

    public static final String DEVELOPED = "developed";
    public static final String WON = "won";
    public static final String LOST = "lost";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "quote_ref", length = 36)
    private String quoteRef;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getHref() { return href; }
    public void setHref(String v) { this.href = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getLeadId() { return leadId; }
    public void setLeadId(String v) { this.leadId = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getQuoteRef() { return quoteRef; }
    public void setQuoteRef(String v) { this.quoteRef = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
