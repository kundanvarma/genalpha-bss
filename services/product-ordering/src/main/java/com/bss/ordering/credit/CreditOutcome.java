package com.bss.ordering.credit;

import com.bss.ordering.entity.CreditDecision;
import com.bss.ordering.entity.ProductOrder;

import java.util.Optional;

/**
 * What a credit decision actually did to the order (#422 review, finding 13)
 * — read off the ORDER as it stands, never off the bureau's answer alone. A
 * decline a person released went ahead; a borderline answer the operator held
 * is on hold; a decline whose order the operator's rule refused was never
 * placed. Each outcome carries the sentence the customer reads, what happens
 * after they ask a person, and the line the care ticket carries.
 */
public enum CreditOutcome {

    HELD("held", "Your order is on hold until a person reviews it.",
            CreditOutcome.NEXT_STEPS_HELD,
            " The order is on hold until a person decides: release it, or cancel it with the customer."),
    RELEASED("released", "A person reviewed your order and let it go ahead.",
            CreditOutcome.NEXT_STEPS,
            " The order already went ahead after a person released it; check whether the customer's plan or terms"
                    + " should change."),
    CANCELLED("cancelled", "Your order was cancelled.",
            CreditOutcome.NEXT_STEPS,
            " The order has been cancelled; if the decision was wrong, help the customer order again."),
    REFUSED_BY_RULE("refusedByRule", "Your order was not placed: this shop's own rules refused it.",
            CreditOutcome.NEXT_STEPS_NOT_PLACED,
            " The order was refused by the operator's rule and was not placed"),
    NOT_PLACED("notPlaced", "Your order was not placed.",
            CreditOutcome.NEXT_STEPS_NOT_PLACED,
            " The order was not placed; if the decision was wrong, help the customer order again."),
    WENT_AHEAD("wentAhead", "Your order went ahead.",
            CreditOutcome.NEXT_STEPS,
            " The order already went ahead; check whether the customer's plan or terms should change."),
    WENT_AHEAD_CLOSE("wentAhead", "Your order went ahead; the credit bureau's answer was close to the line.",
            CreditOutcome.NEXT_STEPS,
            " The order already went ahead; check whether the customer's plan or terms should change."),
    FROZEN("frozen", "The order could not be placed while your credit information is frozen.",
            CreditOutcome.NEXT_STEPS, "");

    static final String NEXT_STEPS_HELD = "A person in customer care will look at your order and the reason, and "
            + "contact you. Your order stays on hold until then — you do not need to order again.";
    static final String NEXT_STEPS = "A person in customer care will look at the decision and the reason, and "
            + "contact you.";
    static final String NEXT_STEPS_NOT_PLACED = "A person in customer care will look at the decision and the "
            + "reason, and contact you. Your order was not placed, so nothing is waiting — if the decision "
            + "changes, they will help you order again.";

    private final String code;
    private final String sentence;
    private final String nextSteps;
    private final String ticketLine;

    CreditOutcome(String code, String sentence, String nextSteps, String ticketLine) {
        this.code = code;
        this.sentence = sentence;
        this.nextSteps = nextSteps;
        this.ticketLine = ticketLine;
    }

    /** The decision and the order as it stands now (empty: the order never committed). */
    public static CreditOutcome of(CreditDecision d, Optional<ProductOrder> order) {
        if (CreditDecisionPort.FROZEN.equals(d.getDecision())) {
            return FROZEN;
        }
        if (order.isEmpty()) {
            return d.getOrderRefusedAt() != null ? REFUSED_BY_RULE : NOT_PLACED;
        }
        String state = order.get().getState();
        if ("held".equals(state)) {
            return HELD;
        }
        if ("cancelled".equals(state)) {
            return CANCELLED;
        }
        if (CreditDecisionPort.DECLINE.equals(d.getDecision())) {
            // a decline always holds; an order past the hold was released by a person
            return RELEASED;
        }
        return CreditDecisionPort.REVIEW.equals(d.getDecision()) ? WENT_AHEAD_CLOSE : WENT_AHEAD;
    }

    public String code() {
        return code;
    }

    public String sentence() {
        return sentence;
    }

    public String nextSteps() {
        return nextSteps;
    }

    /** The sentence the care ticket carries about the order, after the automated answer. */
    public String ticketLine(CreditDecision d) {
        if (this == REFUSED_BY_RULE) {
            return ticketLine + (d.getOrderRefusal() == null ? "." : " (\"" + d.getOrderRefusal() + "\").")
                    + " If the decision was wrong, help the customer order again.";
        }
        return ticketLine;
    }
}
