package com.bss.usage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The subscriber projection every OCS adapter answers with — the generic
 * {@code http} shape the bundled mock-ocs exposes, and what the SigScale
 * adapter projects a TMF637 product into.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "tenantId", "partyId", "serviceId", "ratePlanId", "status", "buckets", "provider"})
public record OcsSubscriber(String id, String tenantId, String partyId, String serviceId, String ratePlanId,
        String status, List<OcsBucket> buckets, String provider) {

    public List<OcsBucket> bucketList() {
        return buckets == null ? List.of() : buckets;
    }

    public boolean hasBucket(String bucketId) {
        return bucketList().stream().anyMatch(b -> bucketId.equals(b.id()));
    }
}
