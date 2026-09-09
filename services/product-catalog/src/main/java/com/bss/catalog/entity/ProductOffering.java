package com.bss.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "product_offering")
public class ProductOffering {

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

    @Column(name = "lifecycle_status")
    private String lifecycleStatus;

    @Column(name = "valid_from")
    private java.time.OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private java.time.OffsetDateTime validTo;

    @Column(name = "announced_at")
    private java.time.OffsetDateTime announcedAt;

    @Column(name = "version")
    private String version;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    /** JSON object referencing the product specification, echoed verbatim. */
    @Column(name = "product_specification", length = 4000)
    private String productSpecificationJson;

    /** JSON list of attachments (offering imagery), echoed verbatim. */
    @Column(name = "attachment", length = 4000)
    private String attachmentJson;

    /** TMF620 channel[]: ChannelRef list as JSON; null/empty = every channel. */
    @Column(name = "channel", length = 2000)
    private String channelJson;

    /** Launch governance (internal, never on the TMF DTO): none|requested|approved|rejected|held|launched|expired. */
    @Column(name = "governance_state", length = 24)
    private String governanceState;

    @Column(name = "governance_json", length = 8000)
    private String governanceJson;

    @Column(name = "launch_hold_until")
    private OffsetDateTime launchHoldUntil;

    @Column(name = "approval_expires_at")
    private OffsetDateTime approvalExpiresAt;

    /** JSON list of commitment terms, echoed verbatim. */
    @Column(name = "product_offering_term", length = 4000)
    private String productOfferingTermJson;

    @Column(name = "is_bundle")
    private Boolean isBundle;

    /** Requires BankID/Vipps-grade verified identity at checkout. */
    @Column(name = "requires_verified_identity")
    private Boolean requiresVerifiedIdentity;

    /** JSON array of child offering references, echoed verbatim. */
    @Column(name = "bundled_product_offering", length = 4000)
    private String bundledProductOfferingJson;

    @Column(name = "category", length = 4000)
    private String categoryJson;

    /** JSON array of price references, echoed verbatim. */
    @Column(name = "product_offering_price", length = 4000)
    private String productOfferingPriceJson;

    public ProductOffering() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getProductSpecificationJson() {
        return productSpecificationJson;
    }

    public void setProductSpecificationJson(String productSpecificationJson) {
        this.productSpecificationJson = productSpecificationJson;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public Boolean getRequiresVerifiedIdentity() {
        return requiresVerifiedIdentity;
    }

    public void setRequiresVerifiedIdentity(Boolean requiresVerifiedIdentity) {
        this.requiresVerifiedIdentity = requiresVerifiedIdentity;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public String getBundledProductOfferingJson() {
        return bundledProductOfferingJson;
    }

    public void setBundledProductOfferingJson(String bundledProductOfferingJson) {
        this.bundledProductOfferingJson = bundledProductOfferingJson;
    }

    public String getCategoryJson() {
        return categoryJson;
    }

    public void setCategoryJson(String categoryJson) {
        this.categoryJson = categoryJson;
    }

    public String getProductOfferingPriceJson() {
        return productOfferingPriceJson;
    }

    public void setProductOfferingPriceJson(String productOfferingPriceJson) {
        this.productOfferingPriceJson = productOfferingPriceJson;
    }

    public String getProductOfferingTermJson() {
        return productOfferingTermJson;
    }

    public void setProductOfferingTermJson(String productOfferingTermJson) {
        this.productOfferingTermJson = productOfferingTermJson;
    }

    public String getAttachmentJson() {
        return attachmentJson;
    }

    public void setAttachmentJson(String attachmentJson) {
        this.attachmentJson = attachmentJson;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public java.time.OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(java.time.OffsetDateTime v) { this.validFrom = v; }
    public java.time.OffsetDateTime getValidTo() { return validTo; }
    public void setValidTo(java.time.OffsetDateTime v) { this.validTo = v; }
    public java.time.OffsetDateTime getAnnouncedAt() { return announcedAt; }
    public void setAnnouncedAt(java.time.OffsetDateTime v) { this.announcedAt = v; }

    public String getChannelJson() { return channelJson; }
    public void setChannelJson(String v) { this.channelJson = v; }

    public String getGovernanceState() { return governanceState; }
    public void setGovernanceState(String governanceState) { this.governanceState = governanceState; }
    public String getGovernanceJson() { return governanceJson; }
    public void setGovernanceJson(String governanceJson) { this.governanceJson = governanceJson; }
    public OffsetDateTime getLaunchHoldUntil() { return launchHoldUntil; }
    public void setLaunchHoldUntil(OffsetDateTime launchHoldUntil) { this.launchHoldUntil = launchHoldUntil; }
    public OffsetDateTime getApprovalExpiresAt() { return approvalExpiresAt; }
    public void setApprovalExpiresAt(OffsetDateTime approvalExpiresAt) { this.approvalExpiresAt = approvalExpiresAt; }
}
