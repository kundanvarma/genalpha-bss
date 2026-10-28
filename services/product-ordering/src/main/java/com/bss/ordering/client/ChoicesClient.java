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
            String offeringName, int cost, List<Source> replaces, Terms terms);

    /**
     * What the catalog says about a benefit at the moment it is picked: the
     * months it is held (null = none), who provides it and what the operator
     * pays them a month (#360) — the ledger keeps this snapshot, so a later
     * catalog change never rewrites what was agreed.
     */
    record Terms(Integer holdMonths, String partner, java.math.BigDecimal wholesaleCost) {

        public static final Terms NONE = new Terms(null, null, null);
    }

    void settle(String orderId, String ownerPartyId, List<Grant> grants, List<Ending> endings, List<Regrant> regrants);

    /** The household's pool as ordering's checks need it: totals, what is in use (and held), this quarter's swaps. */
    Pool pool(String ownerPartyId);

    void release(String orderId);

    /**
     * Where the household stands, for "can I pick this?" (#392): whether this
     * operator offers Choices, its word for one, how many are left, and which
     * benefits are already picked.
     */
    Standing standing(String ownerPartyId);

    record Standing(boolean offered, String label, int left, java.util.Set<String> pickedOfferingIds) {
    }

    /** A benefit's allocation, named by the order item that made it (the product's lineage). */
    record Source(String orderId, String orderItemId) {
    }

    /** A benefit the completing order ends — swapped for another one, or simply given back. */
    record Ending(String orderId, String orderItemId, boolean swapped) {
    }

    /** A plan item whose Choices change as the order completes (0: the plan ends). */
    record Regrant(String orderId, String orderItemId, String offeringId, String offeringName, int choices) {
    }

    record Pool(int granted, int inUse, List<GrantView> grants, List<InUse> allocations, int swapsThisQuarter,
            String nextQuarterStarts) {
    }

    /** A plan's grant as the ledger keeps it, named by the order item that bought the plan. */
    record GrantView(String offeringName, int choices, String orderId, String orderItemId) {
    }

    /** One benefit in use: what it is, the order item that made it, and until when it is held (null: not held). */
    record InUse(String offeringName, int cost, String state, String orderId, String orderItemId, String heldUntil) {
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
