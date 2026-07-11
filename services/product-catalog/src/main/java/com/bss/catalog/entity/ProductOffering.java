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
}
