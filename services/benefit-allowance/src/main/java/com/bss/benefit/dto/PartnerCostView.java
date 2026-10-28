package com.bss.benefit.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the operator owes its partners for one month (#360): one line per
 * partner and benefit, the benefits counted, and the total. Money, in the
 * tenant's currency — the only place Choices ever turn into money.
 */
public record PartnerCostView(String period, String currency, BigDecimal total, List<Line> lines) {

    /** One partner's benefit: how many households had it active this month, and what that costs. */
    public record Line(String partner, String offeringName, int active, BigDecimal amount) {
    }

    /** One recorded month of one active benefit — the event's payload and the revenue posting's source. */
    public record Recorded(String id, String allocationId, String period, String partner, String offeringName,
            BigDecimal amount, String currency, List<ChoiceEvents.PartyRef> relatedParty) {
    }
}
