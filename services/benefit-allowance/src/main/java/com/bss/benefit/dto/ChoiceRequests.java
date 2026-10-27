package com.bss.benefit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * What ordering tells the ledger. Records, not maps: a body cannot carry a
 * field the ledger does not declare. The owner rides the body because the
 * caller is ordering's machine identity acting for a customer it has already
 * authorised — the endpoints that take these are closed to every other token.
 */
public final class ChoiceRequests {

    private ChoiceRequests() {
    }

    /**
     * Hold Choices for one benefit item of an order being placed. In a swap,
     * {@code replaces} names the benefits the same order ends: their Choices
     * count as free for this hold, because the order cannot complete without
     * ending them.
     */
    public record Hold(@NotBlank String ownerPartyId, @NotBlank String orderId, @NotBlank String orderItemId,
            @NotBlank String offeringId, @NotBlank String offeringName, @Min(1) int cost,
            List<@Valid Source> replaces, @Min(0) Integer holdMonths) {

        public Hold(String ownerPartyId, String orderId, String orderItemId, String offeringId, String offeringName,
                int cost) {
            this(ownerPartyId, orderId, orderItemId, offeringId, offeringName, cost, List.of(), null);
        }
    }

    /**
     * A plan item's Choices change as the order completes (#357): a plan change
     * to another offering, or the plan ending ({@code choices} 0). The grant is
     * named by the order item that bought the plan.
     */
    public record Regrant(@NotBlank String orderId, @NotBlank String orderItemId, String offeringId,
            String offeringName, @Min(0) int choices) {
    }

    /**
     * A goodwill Choice (#358): one more Choice for the household, for a reason
     * a person decided. Reaches the ledger only through the governed action
     * grantGoodwillChoice — an agent may ask, a human approves.
     */
    public record Goodwill(@NotBlank String ownerPartyId, @NotBlank String reason) {
    }

    /** An allocation by the order item that made it (the inventory product's lineage). */
    public record Source(@NotBlank String orderId, @NotBlank String orderItemId) {
    }

    /** A benefit the completing order ends: swapped for another, or simply released. */
    public record Ending(@NotBlank String orderId, @NotBlank String orderItemId, boolean swapped) {
    }

    /** One plan item that grants Choices, as the order completes. */
    public record Grant(@NotBlank String orderItemId, @NotBlank String offeringId, @NotBlank String offeringName,
            @Min(1) int choices) {
    }

    /** The order completed: commit what it held, add what it granted, end what it ended. */
    public record Settle(@NotBlank String ownerPartyId, List<@Valid Grant> grants, List<@Valid Ending> endings,
            List<@Valid Regrant> regrants) {

        public Settle(String ownerPartyId, List<Grant> grants) {
            this(ownerPartyId, grants, List.of(), List.of());
        }
    }
}
