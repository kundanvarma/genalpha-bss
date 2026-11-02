package com.bss.ordering.credit;

import com.bss.ordering.client.ReviewTicketClient;
import com.bss.ordering.entity.CreditDecision;
import com.bss.ordering.entity.ProductOrder;
import com.bss.ordering.events.DomainEventPublisher;
import com.bss.ordering.exception.NotFoundException;
import com.bss.ordering.exception.OrderValidationException;
import com.bss.ordering.repository.CreditDecisionRepository;
import com.bss.ordering.repository.ProductOrderRepository;
import com.bss.ordering.security.PartyScope;
import com.bss.ordering.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The customer's side of a credit decision (#422): read why, and ask a person.
 *
 * <p>The order is the door. A customer reaches a decision only through an
 * order they own — the owner comes from the token and the order row, never
 * from a parameter — and another customer's order answers 404, exactly as
 * reading the order itself does. Staff tokens are unscoped, as everywhere in
 * ordering.
 */
@Service
public class CreditExplanationService {

    private static final String ORDER = "ProductOrder";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    static final List<CreditExplanation.DataCategory> DATA_USED = List.of(
            new CreditExplanation.DataCategory("identity",
                    "Who you are: your customer record, which the credit bureau matches to its own register."),
            new CreditExplanation.DataCategory("scoreBand",
                    "The score band the credit bureau gives you."),
            new CreditExplanation.DataCategory("paymentRemarks",
                    "Whether the credit bureau holds a registered payment remark (an unpaid debt) for you."),
            new CreditExplanation.DataCategory("purpose",
                    "What the check is for: signing up to a plan you pay for after use."));

    static final String NOT_KEPT = "We never receive or keep the credit bureau's report — only its decision, "
            + "the score band and whether a payment remark exists.";
    static final String HUMAN_REVIEW = "A person reviews every credit decline: a decline puts the order on hold "
            + "for a person to decide, it never refuses the order on its own.";
    static final String HOW_TO_CONTEST = "Ask for a person to review the decision, and add a note if something is "
            + "wrong — for example a debt that has since been paid. You can also ask the credit bureau for a copy "
            + "of what it holds about you, and have it corrected.";
    static final String NEXT_STEPS_HELD = "A person in customer care will look at your order and the reason, and "
            + "contact you. Your order stays on hold until then — you do not need to order again.";
    static final String NEXT_STEPS = "A person in customer care will look at the decision and the reason, and "
            + "contact you.";

    private final CreditDecisionRepository decisions;
    private final ProductOrderRepository orders;
    private final CreditDecisionService credit;
    private final ReviewTicketClient tickets;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public CreditExplanationService(CreditDecisionRepository decisions, ProductOrderRepository orders,
            CreditDecisionService credit, ReviewTicketClient tickets, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope) {
        this.decisions = decisions;
        this.orders = orders;
        this.credit = credit;
        this.tickets = tickets;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    /** Shown before checkout: does this shop check credit, with whom, and what are my rights. */
    public CreditCheckNotice notice() {
        boolean checked = credit.enabledForCurrentTenant();
        String notice = checked
                ? "Before we accept a plan you pay for after use, we check your credit with " + credit.bureau()
                        + ". The check is automated, but it never refuses you on its own: a person reviews every"
                        + " decline. You can ask why, and ask a person to look again, from My orders."
                : "This shop does not check your credit.";
        return new CreditCheckNotice(checked, credit.bureau(), credit.purpose(), checked ? DATA_USED : List.of(),
                notice);
    }

    /** Why the credit check on this order came out as it did — for the order's owner, or staff. */
    @Transactional(readOnly = true)
    public CreditExplanation explain(String orderId) {
        ProductOrder order = ownOrder(orderId);
        Optional<CreditDecision> found = decisionFor(orderId);
        if (found.isEmpty()) {
            return new CreditExplanation(order.getId(), false, null,
                    "No credit check was recorded for this order.", null, null, null, null, null,
                    credit.bureau(), null, List.of(), NOT_KEPT, HUMAN_REVIEW,
                    new CreditExplanation.Contest(false, null, false, null, null, null));
        }
        CreditDecision d = found.get();
        boolean contestable = contestable(d.getDecision());
        boolean requested = d.getReviewRequestedAt() != null;
        return new CreditExplanation(order.getId(), true, d.getDecision(), outcome(d.getDecision()),
                d.getReasonCode(), d.getReason(), d.getScoreBand(), d.isRemarksPresent(),
                d.getDecidedAt().toString(), credit.bureau(), d.getPurpose(), DATA_USED, NOT_KEPT, HUMAN_REVIEW,
                new CreditExplanation.Contest(contestable, contestable ? HOW_TO_CONTEST : null, requested,
                        requested ? d.getReviewRequestedAt().toString() : null,
                        d.getReviewTicketId(), requested ? nextSteps(d.getDecision()) : null));
    }

    /**
     * The customer asks a person to look again. One request per order: the
     * first claims the decision row and files the ticket; any later one gets
     * the same receipt back. A ticket the care queue did not take rolls the
     * claim back, so the customer can simply try again.
     */
    @Transactional
    public Result requestReview(String orderId, CreditReviewRequest request) {
        String note = request == null || request.note() == null ? null : request.note().strip();
        if (note != null && note.length() > CreditReviewRequest.MAX_NOTE) {
            throw new OrderValidationException("the note is too long — keep it under "
                    + CreditReviewRequest.MAX_NOTE + " characters");
        }
        ProductOrder order = ownOrder(orderId);
        CreditDecision d = decisionFor(orderId).orElseThrow(() -> new OrderValidationException(
                "no credit check was recorded for this order, so there is nothing to review"));
        if (!contestable(d.getDecision())) {
            throw new OrderValidationException(CreditDecisionPort.FROZEN.equals(d.getDecision())
                    ? "a credit freeze is not a decision — lift the freeze with the credit bureau and order again"
                    : "this credit check approved the order, so there is nothing to review");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String tenant = tenantScope.currentTenantId();
        if (decisions.claimReview(d.getId(), tenant, now) == 0) {
            CreditDecision already = decisions.findById(d.getId()).orElse(d);
            return new Result(false, receipt(order, already, true));
        }
        String ticketId = tickets.open(ticketFor(order, d, note));
        CreditDecision claimed = decisions.findById(d.getId()).orElseThrow();
        claimed.setReviewTicketId(ticketId);
        decisions.save(claimed);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", claimed.getId());
        resource.put("orderId", order.getId());
        resource.put("decision", claimed.getDecision());
        resource.put("reasonCode", claimed.getReasonCode());
        resource.put("reviewRequestedAt", claimed.getReviewRequestedAt().toString());
        resource.put("troubleTicket", Map.of("id", ticketId));
        resource.put("relatedParty", List.of(customerRef(order.getOwnerPartyId())));
        events.publish("CreditDecisionReviewRequestedEvent", "creditDecision", resource);
        return new Result(true, receipt(order, claimed, false));
    }

    /** The receipt, and whether this call filed it (201) or found it filed (200). */
    public record Result(boolean created, CreditReviewReceipt receipt) {
    }

    private CreditReviewReceipt receipt(ProductOrder order, CreditDecision d, boolean already) {
        return new CreditReviewReceipt(order.getId(), "requested", already,
                d.getReviewRequestedAt() == null ? null : d.getReviewRequestedAt().toString(),
                d.getReviewTicketId(), nextSteps(d.getDecision()));
    }

    private ReviewTicketClient.ReviewTicket ticketFor(ProductOrder order, CreditDecision d, String note) {
        String placed = order.getOrderDate() == null ? "" : " placed " + DAY.format(order.getOrderDate());
        String held = CreditDecisionPort.DECLINE.equals(d.getDecision())
                ? " The order is on hold until a person decides: release it, or cancel it with the customer."
                : " The order was not held; check whether the customer's plan or terms should change.";
        String description = "The customer asks a person to review the credit decision on their order" + placed
                + ". The automated answer was: " + (d.getReason() == null ? d.getDecision() : d.getReason())
                + held;
        String name = order.getDescription() == null || order.getDescription().isBlank()
                ? "Order" + placed : order.getDescription().replace("[credit-review]", "").strip();
        return new ReviewTicketClient.ReviewTicket(
                "Credit decision review requested",
                description,
                "creditReview",
                "major",
                note == null || note.isBlank() ? List.of() : List.of(new ReviewTicketClient.Note(note)),
                List.of(new ReviewTicketClient.PartyRef(order.getOwnerPartyId(), "customer", "Individual")),
                List.of(new ReviewTicketClient.EntityRef(order.getId(), order.getHref(),
                        name.isBlank() ? "Order" + placed : name, "creditReview", "ProductOrder")));
    }

    private ProductOrder ownOrder(String orderId) {
        ProductOrder order = orders.findByIdAndTenantId(orderId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(ORDER, orderId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(order.getOwnerPartyId())) {
                // 404, never 403: another customer's order does not exist for you
                throw NotFoundException.forResource(ORDER, orderId);
            }
        });
        return order;
    }

    private Optional<CreditDecision> decisionFor(String orderId) {
        return decisions.findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc(tenantScope.currentTenantId(), orderId);
    }

    private static Map<String, Object> customerRef(String partyId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", partyId);
        ref.put("role", "customer");
        return ref;
    }

    /** A decline or a borderline answer can be put to a person; an approval or a freeze cannot. */
    private static boolean contestable(String decision) {
        return CreditDecisionPort.DECLINE.equals(decision) || CreditDecisionPort.REVIEW.equals(decision);
    }

    private static String nextSteps(String decision) {
        return CreditDecisionPort.DECLINE.equals(decision) ? NEXT_STEPS_HELD : NEXT_STEPS;
    }

    /** What the decision did to the order, in plain words. */
    private static String outcome(String decision) {
        return switch (decision == null ? "" : decision) {
            case CreditDecisionPort.DECLINE -> "Your order is on hold until a person reviews it.";
            case CreditDecisionPort.REVIEW -> "Your order went ahead; the credit bureau's answer was close to the line.";
            case CreditDecisionPort.APPROVE -> "Your order went ahead.";
            case CreditDecisionPort.FROZEN -> "The order could not be placed while your credit information is frozen.";
            default -> "The credit bureau's answer was passed to the order rules.";
        };
    }
}
