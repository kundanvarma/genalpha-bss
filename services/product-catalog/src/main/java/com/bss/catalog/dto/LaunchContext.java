package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * What an envelope may look at: the plain facts of an offer in plain units —
 * the lowest price, the allowance in GB, the validity in days, the channels.
 * Sent to policy as the launch-domain context and echoed by the dry-run.
 * Every fact is present (null when the offer does not say) except the
 * specification name, which appears only when a specification is attached.
 */
@JsonPropertyOrder({"name", "category", "categoryId", "price", "priceType", "currency", "specification", "chars",
        "allowanceGb", "validityDays", "zeroRatedApps", "channel", "channelCount", "term", "isBundle"})
public record LaunchContext(String name, List<String> category, List<String> categoryId, Double price, String priceType,
        String currency, @JsonInclude(JsonInclude.Include.NON_NULL) String specification, Map<String, Object> chars,
        Double allowanceGb, Double validityDays, boolean zeroRatedApps, List<String> channel, int channelCount,
        List<String> term, boolean isBundle) {
}
