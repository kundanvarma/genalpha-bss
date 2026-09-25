package com.bss.revenue.service;

import java.util.Map;

/**
 * What each posting key actually books, in the words a controller uses.
 *
 * <p>A posting key is an identifier — {@code rate:recurringCharge} — and an
 * identifier is never a description. The chart of accounts is read by people
 * who know what a recurring charge is and not what a colon means, so the
 * service says the business half and the channel shows the key underneath.
 * It lives here, not in a console, because three channels would otherwise each
 * invent their own words for the same row.
 */
public final class ChartWords {

    private static final Map<String, String> BOOKS = Map.ofEntries(
            Map.entry("ar", "What customers owe. Every invoice issued lands here and every payment clears it."),
            Map.entry("cash", "Money actually received, from the card, the bank or the payment provider."),
            Map.entry("bnpl:receivable", "Money a buy-now-pay-later provider owes us until the payout arrives."),
            Map.entry("rate:recurringCharge", "The monthly price of every subscription on a bill."),
            Map.entry("rate:usageCharge", "What was used beyond the plan — calls, data, messages."),
            Map.entry("rate:discount", "Money given away by a campaign or a price rule."),
            Map.entry("rate:priceAdjustment", "A one-off correction made on a bill line."),
            Map.entry("rate:disputeCredit", "A credit given on a billed line after a customer contested it."),
            Map.entry("dispute", "A credit given after the bill was already posted."),
            Map.entry("rate:creditNote", "A credit note raised against a billed line."),
            Map.entry("creditNote", "A credit note raised against a whole bill."),
            Map.entry("refund", "Money handed back to a customer."),
            Map.entry("tax", "VAT owed to the tax authority. The setting is the rate, in percent."),
            Map.entry("loyalty:expense", "What loyalty points cost us when they are earned."),
            Map.entry("loyalty:liability", "What outstanding loyalty points are worth. The setting is the value of one point."),
            Map.entry("wholesale:cogs", "What another operator's access network costs us."),
            Map.entry("wholesale:payable", "What we owe the network owner for that access."),
            Map.entry("mobile-wholesale:cogs", "What a host mobile network charges us for usage."),
            Map.entry("mobile-wholesale:payable", "What we owe that host network."),
            Map.entry("mobile-wholesale:receivable", "What a virtual operator on our network owes us."),
            Map.entry("mobile-wholesale:revenue", "What we earn from virtual operators riding our network."),
            Map.entry("club-share:expense", "Community sponsorship paid out of a customer's subscription."),
            Map.entry("club-share:payable", "Sponsorship money owed to the clubs until it is paid."),
            Map.entry("device:contract-asset", "A handset already handed over but not yet billed."),
            Map.entry("device:equipment-revenue", "What the handset itself sold for."),
            Map.entry("device:trade-in-inventory", "Traded-in handsets we are holding."),
            Map.entry("device:swap-writeoff", "The loss taken when a handset is swapped out."),
            Map.entry("device:deduction", "Money recovered when a traded-in handset is worth less than promised."),
            Map.entry("device:rvg-expense", "What a residual-value promise costs us."),
            Map.entry("device:rvg-liability", "What we still owe under residual-value promises."));

    private ChartWords() {
    }

    /** What the key books; the key itself when nothing better is known. */
    public static String books(String key) {
        return BOOKS.getOrDefault(key, "A posting rule of this tenant's own.");
    }

    /** Does this key carry a setting, and what is that setting called? */
    public static String setting(String key) {
        if ("tax".equals(key)) {
            return "VAT rate, in percent";
        }
        if ("loyalty:liability".equals(key)) {
            return "value of one loyalty point";
        }
        return null;
    }
}
