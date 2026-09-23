package com.bss.promotion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A campaign as data. The percentage is the entity's own {@code BigDecimal},
 * never re-scaled: the create echo answers at the scale the caller posted
 * (12.5) and a re-read answers at the column's (12.50), exactly as the map
 * path did.
 */
@JsonPropertyOrder({"id", "href", "name", "description", "code", "lifecycleStatus",
        "percentage", "durationMonths", "appliesTo", "lastUpdate", "@type"})
public record PromotionView(
        String id,
        String href,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        String code,
        String lifecycleStatus,
        BigDecimal percentage,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer durationMonths,
        List<String> appliesTo,
        OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) {

    public static PromotionView of(String id, String href, String name, String description,
            String code, String lifecycleStatus, BigDecimal percentage, Integer durationMonths,
            List<String> appliesTo, OffsetDateTime lastUpdate) {
        return new PromotionView(id, href, name, description, code, lifecycleStatus, percentage,
                durationMonths, appliesTo, lastUpdate, "Promotion");
    }

}
