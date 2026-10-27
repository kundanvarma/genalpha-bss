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

    /** Hold Choices for one benefit item of an order being placed. */
    public record Hold(@NotBlank String ownerPartyId, @NotBlank String orderId, @NotBlank String orderItemId,
            @NotBlank String offeringId, @NotBlank String offeringName, @Min(1) int cost) {
    }

    /** One plan item that grants Choices, as the order completes. */
    public record Grant(@NotBlank String orderItemId, @NotBlank String offeringId, @NotBlank String offeringName,
            @Min(1) int choices) {
    }

    /** The order completed: commit what it held, add what it granted. */
    public record Settle(@NotBlank String ownerPartyId, List<@Valid Grant> grants) {
    }
}
