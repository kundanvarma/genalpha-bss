package com.bss.ordering.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A house field beside the TM Forum body (#422 review): "there is a credit
 * decision here a channel should explain", and where to read it. Ordering
 * decides when it is present — a decline or a borderline answer, or one the
 * customer already asked a person about — so no channel works that out
 * itself. On an order it points at the order's explanation; on a refusal
 * (422 POLICY_DENIED) at the decision's own, because the order never existed.
 */
@JsonPropertyOrder({"id", "href"})
public record CreditDecisionLink(String id, String href) {
}
