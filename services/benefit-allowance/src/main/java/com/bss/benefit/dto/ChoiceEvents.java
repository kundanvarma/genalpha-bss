package com.bss.benefit.dto;

import java.util.List;

/**
 * What the ledger tells the rest of the fleet (#361): every Choice event names
 * the household it happened to (TMF relatedParty, role customer) and where its
 * pool stands afterwards, so insight can keep "Choices left" as a fact and
 * campaign can reach the households that have Choices they have not spent —
 * without either of them calling back into this component.
 */
public final class ChoiceEvents {

    private ChoiceEvents() {
    }

    /** The household, the way every TM Forum resource names one. */
    public record PartyRef(String id, String role) {

        public static List<PartyRef> customer(String id) {
            return id == null ? List.of() : List.of(new PartyRef(id, "customer"));
        }
    }

    /** A benefit held, picked, swapped or released. */
    public record Allocation(String id, String offeringId, String offeringName, int cost, String state,
            String endReason, String orderId, String orderItemId, String heldUntil, List<PartyRef> relatedParty,
            int choicesGranted, int choicesLeft) {
    }

    /** A plan (or goodwill) granting Choices, or a plan change re-granting them. */
    public record Grant(String offeringId, String offeringName, int choices, String orderId, String orderItemId,
            List<PartyRef> relatedParty, int choicesGranted, int choicesLeft) {
    }
}
