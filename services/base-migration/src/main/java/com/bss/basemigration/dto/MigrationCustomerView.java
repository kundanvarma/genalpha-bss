package com.bss.basemigration.dto;

import com.bss.basemigration.entity.MigrationCustomer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One subscriber's journey through a wave: where they started, where they are
 * going, whether the change grants them the penalty-free door, and how far the
 * engine has taken them. This is also the payload of every per-customer
 * domain event — unchanged in shape.
 *
 * <p>The four clocks and references the map only wrote once they existed are
 * {@code NON_NULL}, one component at a time.
 */
@JsonPropertyOrder({"id", "planId", "partyId", "productId", "sourceOffering", "targetOffering", "deltaClass",
    "state", "exitRight", "penaltyFreeExit", "scheduledFor", "noticeSentAt", "orderRef", "failureReason"})
public record MigrationCustomerView(
        String id,
        String planId,
        String partyId,
        String productId,
        OfferingRef sourceOffering,
        OfferingRef targetOffering,
        String deltaClass,
        String state,
        boolean exitRight,
        boolean penaltyFreeExit,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scheduledFor,
        @JsonInclude(JsonInclude.Include.NON_NULL) String noticeSentAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String orderRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureReason) {

    public static MigrationCustomerView of(MigrationCustomer c) {
        return new MigrationCustomerView(c.getId(), c.getPlanId(), c.getPartyId(), c.getProductId(),
                OfferingRef.of(c.getSourceOfferingId(), c.getSourceOfferingName()),
                OfferingRef.of(c.getTargetOfferingId(), c.getTargetOfferingName()),
                c.getDeltaClass(), c.getState(), c.isExitRight(), c.isPenaltyFreeExit(),
                c.getScheduledFor() == null ? null : c.getScheduledFor().toString(),
                c.getNoticeSentAt() == null ? null : c.getNoticeSentAt().toString(),
                c.getOrderRef(), c.getFailureReason());
    }

    /** An offering named on the journey. Key order is the order the wire has it. */
    @JsonPropertyOrder({"name", "id"})
    public record OfferingRef(String name, String id) {

        /** A nameless offering shows as an empty name, and a missing id as the literal the map minted. */
        public static OfferingRef of(String id, String name) {
            return new OfferingRef(name == null ? "" : name, String.valueOf(id));
        }
    }
}
