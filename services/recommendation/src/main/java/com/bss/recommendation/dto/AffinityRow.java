package com.bss.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * "Customers who bought this also bought" — an aggregate only. The count is
 * the privacy floor made visible: below the minimum support the row is not
 * here at all, so one basket can never be read back from the page.
 */
@JsonPropertyOrder({"offering", "coOwners"})
public record AffinityRow(AffinityOffering offering, int coOwners) {

    @JsonPropertyOrder({"id", "name"})
    public record AffinityOffering(String id, String name) {
    }

    public static AffinityRow of(String offeringId, String name, int coOwners) {
        return new AffinityRow(new AffinityOffering(offeringId, name), coOwners);
    }
}
