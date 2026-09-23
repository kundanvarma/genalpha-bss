package com.bss.qualification.dto;

import com.bss.qualification.entity.CoverageMap;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/**
 * A footprint row as the API shows it. The access owner and layer are stored
 * but not published here — the wholesale face answers those, this one is the
 * operator's own coverage list, exactly as the map path wrote it.
 */
@JsonPropertyOrder({"id", "href", "technology", "postcodePrefix", "maxDownMbps",
        "maxUpMbps", "note", "lastUpdate", "@type"})
public record CoverageMapView(
        String id,
        String href,
        String technology,
        String postcodePrefix,
        Integer maxDownMbps,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxUpMbps,
        @JsonInclude(JsonInclude.Include.NON_NULL) String note,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static CoverageMapView of(CoverageMap row) {
        return new CoverageMapView(row.getId(), row.getHref(), row.getTechnology(),
                row.getPostcodePrefix(), row.getMaxDownMbps(), row.getMaxUpMbps(),
                row.getNote(), row.getLastUpdate(), "CoverageMap");
    }
}
