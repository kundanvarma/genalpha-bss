package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** A campaign landing page as authored, plus its public URL. */
@JsonPropertyOrder({"id", "slug", "headline", "subhead", "ctaLabel", "utmSource", "logoUrl", "heroImageUrl", "brandColor",
        "ctaUrl", "privacyUrl", "url", "createdAt"})
public record LandingPageView(String id, String slug, String headline, String subhead, String ctaLabel, String utmSource,
        String logoUrl, String heroImageUrl, String brandColor, String ctaUrl, String privacyUrl, String url,
        OffsetDateTime createdAt) {
}
