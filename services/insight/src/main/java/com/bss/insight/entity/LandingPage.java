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

    // --- brand / customization ---
    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Column(name = "hero_image_url", length = 512)
    private String heroImageUrl;

    @Column(name = "brand_color", length = 16)
    private String brandColor;

    /** Optional secondary link (e.g. "learn more") rendered beside the form. */
    @Column(name = "cta_url", length = 512)
    private String ctaUrl;

    @Column(name = "privacy_url", length = 512)
    private String privacyUrl;

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
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getHeroImageUrl() { return heroImageUrl; }
    public void setHeroImageUrl(String heroImageUrl) { this.heroImageUrl = heroImageUrl; }
    public String getBrandColor() { return brandColor; }
    public void setBrandColor(String brandColor) { this.brandColor = brandColor; }
    public String getCtaUrl() { return ctaUrl; }
    public void setCtaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; }
    public String getPrivacyUrl() { return privacyUrl; }
    public void setPrivacyUrl(String privacyUrl) { this.privacyUrl = privacyUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
