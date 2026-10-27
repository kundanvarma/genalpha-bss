package com.bss.ordering.client;

import java.util.List;

/**
 * Order-lifecycle view of the benefit-allowance ledger (#354), the same shape
 * as stock: placing an order HOLDS the Choices its benefits cost, completing
 * it SETTLES (commits what it held, adds what its plans grant), cancelling
 * RELEASES. Only orders that carry a benefit or a granting plan ever call it.
 */
public interface ChoicesClient {

    /** Hold a benefit's cost; in a swap, {@code replaces} names the benefits the same order ends (their Choices count). */
    HoldOutcome hold(String ownerPartyId, String orderId, String orderItemId, String offeringId,
            String offeringName, int cost, List<Source> replaces);

    void settle(String orderId, String ownerPartyId, List<Grant> grants, List<Ending> endings);

    /** The household's swaps this quarter, and when the next quarter starts — for the swap-limit rule. */
    Swaps swaps(String ownerPartyId);

    void release(String orderId);

    /** A benefit's allocation, named by the order item that made it (the product's lineage). */
    record Source(String orderId, String orderItemId) {
    }

    /** A benefit the completing order ends — swapped for another one, or simply given back. */
    record Ending(String orderId, String orderItemId, boolean swapped) {
    }

    record Swaps(int thisQuarter, String nextQuarterStarts) {
    }

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
