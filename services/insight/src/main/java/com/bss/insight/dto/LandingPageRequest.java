package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Author or edit a landing page. URLs and the colour are sanitised by the service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LandingPageRequest(String slug, String headline, String subhead, String ctaLabel, String utmSource, String logoUrl,
        String heroImageUrl, String brandColor, String ctaUrl, String privacyUrl) {
}
