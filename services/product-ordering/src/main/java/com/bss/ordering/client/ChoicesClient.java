package com.bss.ordering.client;

import java.util.List;

/**
 * Order-lifecycle view of the benefit-allowance ledger (#354), the same shape
 * as stock: placing an order HOLDS the Choices its benefits cost, completing
 * it SETTLES (commits what it held, adds what its plans grant), cancelling
 * RELEASES. Only orders that carry a benefit or a granting plan ever call it.
 */
public interface ChoicesClient {

    HoldOutcome hold(String ownerPartyId, String orderId, String orderItemId, String offeringId,
            String offeringName, int cost);

    void settle(String orderId, String ownerPartyId, List<Grant> grants);

    void release(String orderId);

    /** A plan item of the completing order and the Choices it puts in. */
    record Grant(String orderItemId, String offeringId, String offeringName, int choices) {
    }

    record HoldOutcome(boolean ok, String message) {
        public static HoldOutcome held() {
            return new HoldOutcome(true, null);
        }

        public static HoldOutcome refused(String message) {
            return new HoldOutcome(false, message);
        }
    }
}
