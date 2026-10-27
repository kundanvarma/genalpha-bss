package com.bss.benefit.dto;

import java.util.List;

/**
 * A household's Choices, as a screen shows them: what the plans give, what is
 * in use, what is left, under the tenant's own word for a Choice. Names, never
 * ids, are what a person reads; the ids ride along for the next request.
 */
public record PoolView(boolean offered, String label, int granted, int inUse, int left,
        List<Source> grants, List<Allocation> allocations) {

    /** A plan that put Choices in. */
    public record Source(String offeringId, String offeringName, int choices) {
    }

    /** A benefit that took Choices out: held while its order runs, committed once it is active. */
    public record Allocation(String id, String offeringId, String offeringName, int cost, String state,
            String orderId) {
    }
}
