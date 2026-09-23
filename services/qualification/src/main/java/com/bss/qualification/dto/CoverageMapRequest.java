package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A posted footprint row. Every field is taken as text and converted by the
 * service, because the map path took it as text too: a number posted as a
 * string still parses, and a number that is not one still gets the service's
 * own "must be a number" refusal rather than a parser's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageMapRequest(
        String technology,
        String postcodePrefix,
        String maxDownMbps,
        String maxUpMbps,
        String note,
        String accessOwner,
        String accessLayer) {
}
