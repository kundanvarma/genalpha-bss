package com.bss.benefit.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the operator owes its partners for one month (#360): one line per
 * partner and benefit, the benefits counted, and the total. Money, in the
 * tenant's currency — the only place Choices ever turn into money.
 */
public record PartnerCostView(String period, String currency, BigDecimal total, List<Line> lines,
        List<Partner> partners) {

    /**
     * One partner's month, reconciled (#390): what the ledger says the operator owes,
     * what the partner invoiced, and the difference (invoiced - owed: above zero means
     * the partner billed more than the ledger recorded).
     */
    public record Partner(String partner, BigDecimal owed, BigDecimal invoiced, BigDecimal difference,
            List<Invoice> invoices) {
    }

    /** A partner's invoice for the month, and whether the operator has paid it. */
    public record Invoice(String id, String reference, BigDecimal amount, boolean paid, String paidAt) {
    }

    /** The event's view of an invoice — what revenue settles the payable with. */
    public record InvoiceEvent(String id, String partner, String period, String reference, BigDecimal amount,
            String currency) {
    }

    /** One partner's benefit: how many households had it active this month, and what that costs. */
    public record Line(String partner, String offeringName, int active, BigDecimal amount) {
    }

    /** One recorded month of one active benefit — the event's payload and the revenue posting's source. */
    public record Recorded(String id, String allocationId, String period, String partner, String offeringName,
            BigDecimal amount, String currency, List<ChoiceEvents.PartyRef> relatedParty) {
    }
}
