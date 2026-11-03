package com.bss.ordering.credit;

import com.bss.ordering.entity.CreditDecision;
import com.bss.ordering.events.DomainEventPublisher;
import com.bss.ordering.repository.CreditDecisionRepository;
import com.bss.ordering.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the credit port for checkout: enabled per tenant (default ON
 * for dev — flip {@code bss.credit.enabled} or scope with
 * {@code bss.credit.tenants}), FAIL-OPEN on driver errors (the house
 * default: an assessment outage must never block commerce), and every
 * answered assessment leaves a decision record + a
 * CreditDecisionRecordedEvent — in its OWN transaction, so a frozen
 * rejection that rolls the order back still keeps its audit trail.
 */
@Service
public class CreditDecisionService {

    private static final Logger log = LoggerFactory.getLogger(CreditDecisionService.class);

    private final CreditDecisionPort port;
    private final CreditDecisionRepository repository;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final boolean enabled;
    private final Set<String> enabledTenants;
    private final String purpose;
    private final String bureau;

    public CreditDecisionService(CreditDecisionPort port, CreditDecisionRepository repository,
            DomainEventPublisher events, TenantScope tenantScope,
            @Value("${bss.credit.enabled:true}") boolean enabled,
            @Value("${bss.credit.tenants:}") String tenants,
            @Value("${bss.credit.purpose:postpaid-signup}") String purpose,
            @Value("${bss.credit.bureau-name:the credit bureau}") String bureau) {
        this.port = port;
        this.repository = repository;
        this.events = events;
        this.tenantScope = tenantScope;
        this.enabled = enabled;
        this.enabledTenants = tenants == null || tenants.isBlank() ? Set.of()
                : Set.of(tenants.split("\\s*,\\s*"));
        this.purpose = purpose;
        this.bureau = bureau;
    }

    /** What the check is for — the legitimate purpose the bureau is asked under. */
    public String purpose() {
        return purpose;
    }

    /** Who is asked: the bureau's name as the operator wants customers to read it. */
    public String bureau() {
        return bureau;
    }

    /** Enabled globally, and (when a tenant list is set) for THIS tenant. */
    public boolean enabledForCurrentTenant() {
        return enabled && (enabledTenants.isEmpty()
                || enabledTenants.contains(tenantScope.currentTenantId()));
    }

    /**
     * Assess the ordering party — the opaque reference handed to the driver
     * is the party id; a real bureau driver resolves it to a national id
     * inside its own walls. Returns null when disabled or on driver failure
     * (fail-open). The CALLER records the answer via {@link #record} (a
     * separate proxy call, so its REQUIRES_NEW transaction really opens)
     * before acting on it.
     */
    public CreditDecisionPort.Decision assessParty(String partyId) {
        if (!enabledForCurrentTenant() || partyId == null) {
            return null;
        }
        try {
            return port.assess(partyId, purpose);
        } catch (Exception e) {
            log.warn("credit bureau unreachable, order proceeds unassessed (fail-open): {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * REQUIRES_NEW on purpose: the record (and its outbox event) must
     * survive the order transaction rolling back on a frozen/declined
     * rejection — the audit trail IS the point of storing decisions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String record(String partyId, String orderId, CreditDecisionPort.Decision decision) {
        CreditReason reason = CreditReason.of(decision);
        CreditDecision row = new CreditDecision();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setPartyId(partyId);
        row.setDecision(decision.decision());
        row.setScoreBand(decision.scoreBand());
        row.setRemarksPresent(decision.remarksPresent());
        row.setPurpose(purpose);
        row.setDecidedAt(OffsetDateTime.now());
        row.setOrderId(orderId);
        row.setReasonCode(reason.code());
        row.setReason(reason.sentence());
        repository.save(row);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", row.getId());
        resource.put("partyId", partyId);
        resource.put("decision", decision.decision());
        if (decision.scoreBand() != null) {
            resource.put("scoreBand", decision.scoreBand());
        }
        resource.put("remarksPresent", decision.remarksPresent());
        resource.put("purpose", purpose);
        resource.put("decidedAt", row.getDecidedAt().toString());
        if (orderId != null) {
            resource.put("orderId", orderId);
        }
        resource.put("reasonCode", reason.code());
        resource.put("reason", reason.sentence());
        resource.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        events.publish("CreditDecisionRecordedEvent", "creditDecision", resource);
        return row.getId();
    }

    /**
     * The operator's policy rule refused the order this decision was taken for
     * (#422 review). The order rolls back; this survives it (REQUIRES_NEW, like
     * {@link #record}), so the decision says what happened and stays
     * explainable and contestable by its own id. Answers the link the 422
     * should carry — only when the customer has something to ask a person
     * about; null otherwise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public com.bss.ordering.dto.CreditDecisionLink refusedByRule(String decisionId, String ruleMessage) {
        CreditDecision row = repository.findById(decisionId)
                .filter(d -> d.getTenantId().equals(tenantScope.currentTenantId()))
                .orElse(null);
        if (row == null) {
            return null;
        }
        row.setOrderRefusedAt(OffsetDateTime.now());
        row.setOrderRefusal(ruleMessage == null || ruleMessage.length() <= 400 ? ruleMessage
                : ruleMessage.substring(0, 400));
        repository.save(row);
        return worthExplaining(row) ? new com.bss.ordering.dto.CreditDecisionLink(row.getId(),
                com.bss.ordering.api.ApiConstants.BASE_PATH + "/creditDecision/" + row.getId()) : null;
    }

    /**
     * Whether a channel should show this decision's explanation to the customer
     * unprompted: a decline or a borderline answer (the two a person can be
     * asked about), or one they already asked about. The one place this is
     * decided — the shop only reads the answer (#422 review, finding 12).
     */
    public static boolean worthExplaining(CreditDecision d) {
        return CreditDecisionPort.DECLINE.equals(d.getDecision()) || CreditDecisionPort.REVIEW.equals(d.getDecision())
                || d.getReviewRequestedAt() != null;
    }

    /** For a page of orders: the link to each one's explanation, where it is worth showing. */
    @Transactional(readOnly = true)
    public Map<String, com.bss.ordering.dto.CreditDecisionLink> explanationLinks(java.util.Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<String, com.bss.ordering.dto.CreditDecisionLink> links = new LinkedHashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        // newest first: an order is judged by its latest decision, as the explanation reads it
        for (CreditDecision d : repository.findByTenantIdAndOrderIdInOrderByDecidedAtDesc(
                tenantScope.currentTenantId(), orderIds)) {
            if (seen.add(d.getOrderId()) && worthExplaining(d)) {
                links.put(d.getOrderId(), new com.bss.ordering.dto.CreditDecisionLink(d.getId(),
                        com.bss.ordering.api.ApiConstants.BASE_PATH + "/productOrder/" + d.getOrderId()
                                + "/creditDecision"));
            }
        }
        return links;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> decisionsFor(String partyId) {
        return repository
                .findByTenantIdAndPartyIdOrderByDecidedAtDesc(tenantScope.currentTenantId(), partyId)
                .stream().map(row -> {
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("id", row.getId());
                    out.put("partyId", row.getPartyId());
                    out.put("decision", row.getDecision());
                    if (row.getScoreBand() != null) {
                        out.put("scoreBand", row.getScoreBand());
                    }
                    out.put("remarksPresent", row.isRemarksPresent());
                    out.put("purpose", row.getPurpose());
                    out.put("decidedAt", row.getDecidedAt().toString());
                    // #422: older rows carry no reason or order — the keys are left off
                    if (row.getOrderId() != null) {
                        out.put("orderId", row.getOrderId());
                    }
                    if (row.getReasonCode() != null) {
                        out.put("reasonCode", row.getReasonCode());
                        out.put("reason", row.getReason());
                    }
                    if (row.getReviewRequestedAt() != null) {
                        out.put("reviewRequestedAt", row.getReviewRequestedAt().toString());
                    }
                    // #422 review: the operator's rule refused the order, so there is no order to open
                    if (row.getOrderRefusedAt() != null) {
                        out.put("orderRefusedAt", row.getOrderRefusedAt().toString());
                        out.put("orderRefusal", row.getOrderRefusal());
                    }
                    return out;
                }).toList();
    }
}
