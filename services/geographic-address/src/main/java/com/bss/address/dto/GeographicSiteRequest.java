package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A posted or patched site. The place may arrive as an object or a list and
 * the related parties are stored verbatim, so those two stay open; an absent
 * field and an explicit null mean the same thing on the patch, exactly as
 * they did when the body was a map.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeographicSiteRequest(
        String name,
        String description,
        String status,
        Object relatedParty,
        Object place) {
}
