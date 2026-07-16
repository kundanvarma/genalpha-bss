package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A FORMAT PROFILE AS DATA: everything the renderer needs to know about
 * a country's e-invoice profile — the syntax (ubl | cii), the
 * CustomizationID/ProfileID the document declares, and whether a payment
 * reference is required. Adding a country is an INSERT; the tenant's
 * distribution format picks a row by {@link #code}.
 */
@Entity
@Table(name = "bill_format_profile")
public class BillFormatProfile {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** The key the tenant's BILL_DISTRIBUTION_FORMAT points at. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    /** ubl | cii — which renderer skeleton carries the document. */
    @Column(nullable = false)
    private String syntax;

    @Column(name = "customization_id")
    private String customizationId;

    @Column(name = "profile_id")
    private String profileId;

    /** Norway's NO-R rules want a payment reference (the KID slot). */
    @Column(name = "payment_reference", nullable = false)
    private boolean paymentReference;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSyntax() { return syntax; }
    public void setSyntax(String syntax) { this.syntax = syntax; }
    public String getCustomizationId() { return customizationId; }
    public void setCustomizationId(String customizationId) { this.customizationId = customizationId; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }
    public boolean isPaymentReference() { return paymentReference; }
    public void setPaymentReference(boolean paymentReference) { this.paymentReference = paymentReference; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
