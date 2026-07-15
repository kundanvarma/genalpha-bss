package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** TMF699 salesLead: an interaction with a PROSPECT — the stage before
 * anyone is a customer. Qualifying it mints a salesOpportunity. */
@Entity
@Table(name = "sales_lead")
public class SalesLead {

    public static final String ACKNOWLEDGED = "acknowledged";
    public static final String QUALIFIED = "qualified";
    public static final String UNQUALIFIED = "unqualified";

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

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "company")
    private String company;

    /** Where the lead came from: storefront, campaign, csr, import… */
    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "opportunity_id", length = 36)
    private String opportunityId;

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
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { this.contactEmail = v; }
    public String getCompany() { return company; }
    public void setCompany(String v) { this.company = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getOpportunityId() { return opportunityId; }
    public void setOpportunityId(String v) { this.opportunityId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
