package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** TMF654 Bucket: the live projection of one OCS counter for a party. */
@JsonPropertyOrder({"id", "@type", "name", "ratePlanId", "subscriberId", "serviceId", "remainingValue", "usedValue",
        "rolloverValue", "isRolloverEligible", "relatedParty"})
public record PrepayBucketView(String id, @JsonProperty("@type") String type, String name, String ratePlanId,
        String subscriberId, String serviceId, Amount remainingValue, Amount usedValue, Amount rolloverValue,
        boolean isRolloverEligible, List<RelatedPartyRef> relatedParty) {
}
