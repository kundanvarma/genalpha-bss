package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "individual")
public class Individual {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "given_name")
    private String givenName;

    @Column(name = "family_name", nullable = false)
    private String familyName;

    /** JSON array of TMF632 contact media (postal address, email, ...), echoed verbatim. */
    @Column(name = "contact_medium", length = 4000)
    private String contactMediumJson;

    @Column(name = "organization_id", length = 36)
    private String organizationId;

    public Individual() {
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

    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getContactMediumJson() {
        return contactMediumJson;
    }

    public void setContactMediumJson(String contactMediumJson) {
        this.contactMediumJson = contactMediumJson;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
}
