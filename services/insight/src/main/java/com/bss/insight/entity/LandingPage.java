package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A campaign landing page — a standalone acquisition surface (distinct from the
 * storefront) that an ad or an email deep-links to. It renders a headline + copy
 * + a lead-capture form; a consented submission becomes a prospect stamped with
 * the page's campaign source, so the acquisition loop closes:
 * ad/email → landing → captured lead → prospect audience → nurture.
 */
@Entity
@Table(name = "landing_page")
public class LandingPage {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    /** URL-safe key: /insight/v1/landing/{slug}/view — unique per tenant. */
    @Column(name = "slug")
    private String slug;

    @Column(name = "headline")
    private String headline;

    @Column(name = "subhead", length = 2000)
    private String subhead;

    @Column(name = "cta_label")
    private String ctaLabel;

    /** The campaign this page belongs to — a consented lead is stamped with it. */
    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getSubhead() { return subhead; }
    public void setSubhead(String subhead) { this.subhead = subhead; }
    public String getCtaLabel() { return ctaLabel; }
    public void setCtaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; }
    public String getUtmSource() { return utmSource; }
    public void setUtmSource(String utmSource) { this.utmSource = utmSource; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
