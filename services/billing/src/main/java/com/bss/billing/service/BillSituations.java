package com.bss.billing.service;

import com.bss.billing.dto.BillSituation;
import com.bss.billing.dto.Money;
import com.bss.billing.entity.CustomerBill;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The one place a bill's situation is decided. Pure: facts in, a situation
 * out, no repository and no clock of its own, so every combination that
 * changes the answer is a unit test rather than a fleet.
 *
 * <p>The precedence, highest first, and the reason each rung exists:
 *
 * <ol>
 *   <li><b>writtenOff</b> — the money was given up on; nothing else can be true.</li>
 *   <li><b>paid</b> — settled, or nothing left owing. A paid bill is never late.</li>
 *   <li><b>disputed</b> — the amount is contested, so collection never chases it
 *       (the same rule the collections sweep already applies).</li>
 *   <li><b>arrangement</b> — an approved promise to pay still standing. THIS is the
 *       rung the UX paper is about: a granted extension stops the bill reading
 *       as overdue, in every channel, the moment it is granted.</li>
 *   <li><b>overdue</b> — past the day it is due with money still owing.</li>
 *   <li><b>partiallyPaid</b> — some of it has been paid and the rest is not late yet.</li>
 *   <li><b>outstanding</b> — owed, not yet due. The normal, calm state.</li>
 *   <li><b>issued</b> — raised with nothing to collect (a nil bill).</li>
 * </ol>
 */
public final class BillSituations {

    private BillSituations() {
    }

    /**
     * Everything the answer depends on. Anything absent is null or zero and
     * the calculator says so rather than guessing.
     *
     * @param state          the bill's stored state (new · settled · partiallyPaid · writtenOff)
     * @param amountDue      what the bill was raised for
     * @param allocated      what has been paid or allocated against it so far
     * @param currency       the bill's unit, carried into the answer
     * @param dueDate        the day it falls due (null for a bill raised before due dates existed)
     * @param today          the tenant's today
     * @param openDispute    whether a dispute is open on this bill
     * @param promiseDueDate the standing promise-to-pay date on the account's collection case, else null
     */
    public record Facts(String state, BigDecimal amountDue, BigDecimal allocated, String currency,
            LocalDate dueDate, LocalDate today, boolean openDispute, LocalDate promiseDueDate) {
    }

    public static BillSituation of(Facts f) {
        BigDecimal due = f.amountDue() == null ? BigDecimal.ZERO : f.amountDue();
        BigDecimal paid = f.allocated() == null ? BigDecimal.ZERO : f.allocated();
        BigDecimal owing = due.subtract(paid);
        Money outstanding = new Money(f.currency(), owing.max(BigDecimal.ZERO));
        LocalDate originalDue = f.dueDate();
        boolean arrangement = f.promiseDueDate() != null && f.today() != null
                && !f.promiseDueDate().isBefore(f.today());
        LocalDate currentDue = arrangement ? f.promiseDueDate() : originalDue;

        if (CustomerBill.WRITTEN_OFF.equals(f.state())) {
            return new BillSituation(BillSituation.WRITTEN_OFF, "Written off — no longer collected",
                    originalDue, currentDue, null, outstanding);
        }
        if (CustomerBill.SETTLED.equals(f.state()) || owing.signum() <= 0 && paid.signum() > 0) {
            return new BillSituation(BillSituation.PAID, "Paid in full",
                    originalDue, currentDue, null, new Money(f.currency(), BigDecimal.ZERO));
        }
        if (f.openDispute()) {
            return new BillSituation(BillSituation.DISPUTED, "Disputed — not chased while the dispute is open",
                    originalDue, currentDue, arrangement ? f.promiseDueDate() : null, outstanding);
        }
        if (arrangement) {
            return new BillSituation(BillSituation.ARRANGEMENT,
                    "Payment arrangement in place until " + f.promiseDueDate(),
                    originalDue, currentDue, f.promiseDueDate(), outstanding);
        }
        boolean late = originalDue != null && f.today() != null && originalDue.isBefore(f.today());
        if (late && owing.signum() > 0) {
            return new BillSituation(BillSituation.OVERDUE, "Overdue since " + originalDue,
                    originalDue, currentDue, null, outstanding);
        }
        // the stored state counts too: a bill marked partially paid is part
        // paid even when the amount allocated is not itemised anywhere
        boolean partly = paid.signum() > 0 || CustomerBill.PARTIALLY_PAID.equals(f.state());
        if (partly && owing.signum() > 0) {
            return new BillSituation(BillSituation.PARTIALLY_PAID, "Part paid — the rest is due " + dueWording(originalDue),
                    originalDue, currentDue, null, outstanding);
        }
        if (owing.signum() > 0) {
            return new BillSituation(BillSituation.OUTSTANDING, "Outstanding — due " + dueWording(originalDue),
                    originalDue, currentDue, null, outstanding);
        }
        return new BillSituation(BillSituation.ISSUED, "Issued — nothing to collect",
                originalDue, currentDue, null, outstanding);
    }

    private static String dueWording(LocalDate due) {
        return due == null ? "on the tenant's payment term" : String.valueOf(due);
    }
}
