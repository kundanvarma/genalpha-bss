package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A NOT-YET-a-customer the marketing team may nurture: a captured lead, a list
 * import, a social lead-form. Distinct from a customer (no account, no BSS
 * traits) and from an anonymous visitor (identified — has an address).
 *
 * <p>Consent is the spine, deliberately: {@code consent} + {@code lawfulBasis} +
 * {@code source} travel with every prospect so owned-channel sends can be gated
 * at source. A bought list imports as {@code unconsented} and is captured but NOT
 * reachable until a lawful basis is recorded — the tool enforces the law rather
 * than ignoring it. Keyed by email per tenant so re-imports are idempotent.
 */
@Entity
@Table(name = "prospect")
public class Prospect {

    /** A lead the operator obtained and MAY contact (own capture, consented form). */
    public static final String CONSENTED = "consented";
    /** Imported/known but no lawful basis to market yet (e.g. a bought list). */
    public static final String UNCONSENTED = "unconsented";

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "name")
    private String name;

    /** Where the prospect came from — web-coverage, event, purchased:acme, social:meta… */
    @Column(name = "source")
    private String source;

    /** consented | unconsented — gates every owned-channel send. */
    @Column(name = "consent")
    private String consent;

    /** The recorded basis to contact: opt-in, contract, legitimate-interest-b2b… */
    @Column(name = "lawful_basis")
    private String lawfulBasis;

    /** Social platform's lead id (Meta/LinkedIn Lead Ads) — legit, consented import. */
    @Column(name = "social_ref")
    private String socialRef;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getConsent() { return consent; }
    public void setConsent(String consent) { this.consent = consent; }
    public String getLawfulBasis() { return lawfulBasis; }
    public void setLawfulBasis(String lawfulBasis) { this.lawfulBasis = lawfulBasis; }
    public String getSocialRef() { return socialRef; }
    public void setSocialRef(String socialRef) { this.socialRef = socialRef; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
