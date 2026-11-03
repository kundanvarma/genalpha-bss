package com.bss.ordering.credit;

import com.bss.ordering.api.ApiConstants;
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
import com.bss.ordering.service.ProductOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The customer's side of a credit decision (#422): read why, and ask a person.
 *
 * <p>Two doors, one wall. A customer reaches a decision through an order they
 * own, or — when the operator's rule refused the order and it never existed —
 * through the decision itself, which names them. Either way the owner comes
 * from the token and the store, never from a parameter: another customer's
 * answers 404, as reading their order does; a business admin reaches only the
 * members of its own organization (the same wall order creation applies); staff
 * tokens are unscoped, as everywhere in ordering.
 */
@Service
public class CreditExplanationService {

    private static final String ORDER = "ProductOrder";
    private static final String DECISION = "CreditDecision";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    /** Longer than any ticket call can take (read timeout 30 s): a claim older than this, without a ticket, died. */
    static final Duration IN_FLIGHT = Duration.ofMinutes(2);

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
    static final String HUMAN_REVIEW = "A credit decline is never final without a way to a person: a declined "
            + "order waits on hold for a person to decide, and if this shop's own rules refused the order, you can "
            + "still ask a person to review the decision.";
    static final String HOW_TO_CONTEST = "Ask for a person to review the decision, and add a note if something is "
            + "wrong — for example a debt that has since been paid. You can also ask the credit bureau for a copy "
            + "of what it holds about you, and have it corrected.";

    private final CreditDecisionRepository decisions;
    private final ProductOrderRepository orders;
    private final CreditDecisionService credit;
    private final ProductOrderService orderService;
    private final ReviewTicketClient tickets;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    public CreditExplanationService(CreditDecisionRepository decisions, ProductOrderRepository orders,
            CreditDecisionService credit, ProductOrderService orderService, ReviewTicketClient tickets,
            DomainEventPublisher events, PartyScope partyScope, TenantScope tenantScope,
            PlatformTransactionManager transactions) {
        this.decisions = decisions;
        this.orders = orders;
        this.credit = credit;
        this.orderService = orderService;
        this.tickets = tickets;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.tx = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    /** Shown before checkout: does this shop check credit, with whom, and what are my rights. */
    public CreditCheckNotice notice() {
        boolean checked = credit.enabledForCurrentTenant();
        String notice = checked
                ? "Before we accept a plan you pay for after use, we check your credit with " + credit.bureau()
                        + ". The check is automated, but a decline is never final without a way to a person:"
                        + " you can ask why, and ask a person to look again."
                : "This shop does not check your credit.";
        return new CreditCheckNotice(checked, credit.bureau(), credit.purpose(), checked ? DATA_USED : List.of(),
                notice);
    }

    /** Why the credit check on this order came out as it did — for the order's owner, or staff. */
    public CreditExplanation explain(String orderId) {
        return read.execute(s -> {
            ProductOrder order = ownOrder(orderId);
            return decisionFor(orderId)
                    .map(d -> explanation(d, Optional.of(order)))
                    .orElseGet(() -> new CreditExplanation(order.getId(), null, false, false, null, null,
                            "No credit check was recorded for this order.", null, null, null, null, null, null,
                            credit.bureau(), null, List.of(), NOT_KEPT, HUMAN_REVIEW,
                            new CreditExplanation.Contest(false, null, false, null, null, null)));
        });
    }

    /** The same answer by the decision's own id — the door when the order was never placed. */
    public CreditExplanation explainDecision(String decisionId) {
        return read.execute(s -> {
            CreditDecision d = ownDecision(decisionId);
            return explanation(d, orderOf(d));
        });
    }

    /**
     * The customer asks a person to look again. One request per decision:
     * the first claims it (committed on its own), files the ticket with NO
     * transaction open — the care queue may be slow, and no row lock waits on
     * it — and records the ticket after. A ticket call that failed gives the
     * claim back so the customer can retry; the ticket client finds a ticket
     * the queue took but never confirmed (it names this decision), so a retry
     * after a timeout does not file a second one.
     */
    public Result requestReview(String orderId, CreditReviewRequest request) {
        String note = note(request);
        ProductOrder order = read.execute(s -> ownOrder(orderId));
        CreditDecision d = read.execute(s -> decisionFor(orderId)).orElseThrow(() -> new OrderValidationException(
                "no credit check was recorded for this order, so there is nothing to review"));
        return review(d, Optional.of(order), note);
    }

    /** Ask a person by the decision's own id — for a decision whose order was never placed. */
    public Result requestDecisionReview(String decisionId, CreditReviewRequest request) {
        String note = note(request);
        CreditDecision d = read.execute(s -> ownDecision(decisionId));
        return review(d, read.execute(s -> orderOf(d)), note);
    }

    /** The receipt, and whether this call filed it (201) or found it filed (200). */
    public record Result(boolean created, CreditReviewReceipt receipt) {
    }

    private Result review(CreditDecision d, Optional<ProductOrder> order, String note) {
        if (!contestable(d.getDecision())) {
            throw new OrderValidationException(CreditDecisionPort.FROZEN.equals(d.getDecision())
                    ? "a credit freeze is not a decision — lift the freeze with the credit bureau and order again"
                    : "this credit check approved the order, so there is nothing to review");
        }
        String tenant = tenantScope.currentTenantId();
        // the clock in millis: the column keeps less than nanoseconds, and the claim is compared by value
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        if (tx.execute(s -> decisions.claimReview(d.getId(), tenant, now)) == 0) {
            CreditDecision current = read.execute(s -> decisions.findById(d.getId())).orElse(d);
            boolean abandoned = current.getReviewTicketId() == null && current.getReviewRequestedAt() != null
                    && current.getReviewRequestedAt().isBefore(now.minus(IN_FLIGHT));
            if (!abandoned || tx.execute(s -> decisions.reclaimReview(d.getId(), tenant,
                    current.getReviewRequestedAt(), now)) == 0) {
                // filed — or a first request still with the care queue: the same receipt, never a second ticket
                return new Result(false, receipt(current, order, true));
            }
        }
        String ticketId;
        try {
            ticketId = tickets.open(ticketFor(d, order, note));
        } catch (RuntimeException e) {
            tx.execute(s -> decisions.releaseReview(d.getId(), tenant, now));
            throw e;
        }
        return tx.execute(s -> {
            decisions.recordReviewTicket(d.getId(), tenant, ticketId);
            CreditDecision claimed = decisions.findById(d.getId()).orElseThrow();
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("id", claimed.getId());
            order.ifPresent(o -> resource.put("orderId", o.getId()));
            resource.put("decision", claimed.getDecision());
            resource.put("reasonCode", claimed.getReasonCode());
            resource.put("reviewRequestedAt", claimed.getReviewRequestedAt().toString());
            resource.put("troubleTicket", Map.of("id", ticketId));
            resource.put("relatedParty", List.of(customerRef(claimed.getPartyId())));
            events.publish("CreditDecisionReviewRequestedEvent", "creditDecision", resource);
            return new Result(true, receipt(claimed, order, false));
        });
    }

    private CreditExplanation explanation(CreditDecision d, Optional<ProductOrder> order) {
        CreditOutcome outcome = CreditOutcome.of(d, order);
        boolean contestable = contestable(d.getDecision());
        boolean requested = d.getReviewRequestedAt() != null;
        return new CreditExplanation(order.map(ProductOrder::getId).orElse(null), d.getId(), true,
                CreditDecisionService.worthExplaining(d), d.getDecision(), outcome.code(), outcome.sentence(),
                outcome == CreditOutcome.REFUSED_BY_RULE ? d.getOrderRefusal() : null,
                d.getReasonCode(), d.getReason(), d.getScoreBand(), d.isRemarksPresent(),
                d.getDecidedAt().toString(), credit.bureau(), d.getPurpose(), DATA_USED, NOT_KEPT, HUMAN_REVIEW,
                new CreditExplanation.Contest(contestable, contestable ? HOW_TO_CONTEST : null, requested,
                        requested ? d.getReviewRequestedAt().toString() : null,
                        d.getReviewTicketId(), requested ? outcome.nextSteps() : null));
    }

    private CreditReviewReceipt receipt(CreditDecision d, Optional<ProductOrder> order, boolean already) {
        return new CreditReviewReceipt(order.map(ProductOrder::getId).orElse(null), d.getId(), "requested",
                already, d.getReviewRequestedAt() == null ? null : d.getReviewRequestedAt().toString(),
                d.getReviewTicketId(), CreditOutcome.of(d, order).nextSteps());
    }

    private ReviewTicketClient.ReviewTicket ticketFor(CreditDecision d, Optional<ProductOrder> order, String note) {
        String placed = order.map(o -> o.getOrderDate() == null ? "" : " placed " + DAY.format(o.getOrderDate()))
                .orElse("");
        String asked = order.isPresent() ? "on their order" + placed : "taken when they tried to order";
        String description = "The customer asks a person to review the credit decision " + asked
                + ". The automated answer was: " + (d.getReason() == null ? d.getDecision() : d.getReason())
                + CreditOutcome.of(d, order).ticketLine(d);
        List<ReviewTicketClient.EntityRef> refs = new ArrayList<>();
        order.ifPresent(o -> {
            String name = o.getDescription() == null || o.getDescription().isBlank()
                    ? "Order" + placed : o.getDescription().replace("[credit-review]", "").strip();
            refs.add(new ReviewTicketClient.EntityRef(o.getId(), o.getHref(),
                    name.isBlank() ? "Order" + placed : name, "creditReview", ORDER));
        });
        // the decision itself, always: the reference a retry finds this ticket by
        refs.add(new ReviewTicketClient.EntityRef(d.getId(), ApiConstants.BASE_PATH + "/creditDecision/" + d.getId(),
                "Credit decision of " + DAY.format(d.getDecidedAt()), "creditDecision", DECISION));
        return new ReviewTicketClient.ReviewTicket(
                "Credit decision review requested",
                description,
                "creditReview",
                "major",
                note == null || note.isBlank() ? List.of() : List.of(new ReviewTicketClient.Note(note)),
                List.of(new ReviewTicketClient.PartyRef(d.getPartyId(), "customer", "Individual")),
                refs);
    }

    private static String note(CreditReviewRequest request) {
        String note = request == null || request.note() == null ? null : request.note().strip();
        if (note != null && note.length() > CreditReviewRequest.MAX_NOTE) {
            throw new OrderValidationException("the note is too long — keep it under "
                    + CreditReviewRequest.MAX_NOTE + " characters");
        }
        return note;
    }

    private ProductOrder ownOrder(String orderId) {
        ProductOrder order = orders.findByIdAndTenantId(orderId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(ORDER, orderId));
        requireReach(order.getOwnerPartyId(), ORDER, orderId);
        return order;
    }

    private CreditDecision ownDecision(String decisionId) {
        CreditDecision d = decisions.findByIdAndTenantId(decisionId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(DECISION, decisionId));
        requireReach(d.getPartyId(), DECISION, decisionId);
        return d;
    }

    /**
     * The customer's own, or a member of the business admin's own organization
     * — anything else is 404, never 403: it does not exist for you.
     */
    private void requireReach(String owner, String resource, String id) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(owner)) {
                throw NotFoundException.forResource(resource, id);
            }
        });
        try {
            orderService.requireSameOrgForBusinessAdmin(owner);
        } catch (OrderValidationException outside) {
            throw NotFoundException.forResource(resource, id);
        }
    }

    private Optional<ProductOrder> orderOf(CreditDecision d) {
        return d.getOrderId() == null ? Optional.empty()
                : orders.findByIdAndTenantId(d.getOrderId(), tenantScope.currentTenantId());
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
}
