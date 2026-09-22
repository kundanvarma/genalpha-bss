package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * TMF677 UsageConsumptionReport: this month's buckets for one party, plus
 * the household pool section (a TMF677 extension) when the party draws one.
 */
@JsonPropertyOrder({"id", "href", "name", "effectiveDate", "@type", "relatedParty", "period", "bucket", "pool"})
public record ConsumptionReport(String id, String href, String name, String effectiveDate,
        @JsonProperty("@type") String type, List<RelatedPartyRef> relatedParty, TimePeriod period,
        List<ConsumptionBucket> bucket,
        @JsonInclude(JsonInclude.Include.NON_NULL) PoolView pool) {

    public boolean hasBucket(String bucketId) {
        return bucket.stream().anyMatch(b -> bucketId.equals(b.id()));
    }
}
