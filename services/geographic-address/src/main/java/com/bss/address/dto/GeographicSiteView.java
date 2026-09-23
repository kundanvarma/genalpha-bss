package com.bss.address.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * A named place with a lifecycle. The related parties are the caller's own
 * references, stored and answered verbatim; the place is embedded from the
 * TMF673 row the site leans on, and is left off when that row is gone.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "status", "relatedParty",
        "place", "lastUpdate", "@type"})
public record GeographicSiteView(
        String id,
        String href,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Map<String, Object>> relatedParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<SitePlace> place,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static GeographicSiteView of(String id, String href, String name, String description,
            String status, List<Map<String, Object>> relatedParty, SitePlace place,
            OffsetDateTime lastUpdate) {
        return new GeographicSiteView(id, href, name, description, status, relatedParty,
                place == null ? null : List.of(place), lastUpdate, "GeographicSite");
    }
}
