package com.bss.campaign.decision;

import com.bss.campaign.decision.policy.HoldoutThenWeightedHashPolicy;
import com.bss.campaign.decision.policy.PriorityFirstPolicy;
import com.bss.campaign.decision.policy.ZThresholdTunerPolicy;
import com.bss.campaign.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE DECISION SEAM. Every adaptive choice this service makes goes through
 * {@link #decide}: constraints filter the candidates first, the registered
 * policy picks among what is left, a fallback answers when the policy cannot,
 * and the whole thing — context, eligible actions, chosen action, policy and
 * version, propensity, reason — is written as one {@code DecisionRecordedEvent}
 * with a decision id the caller stores beside its row. When the outcome
 * arrives (a conversion, an adoption) {@link #outcome} joins it back by that id.
 *
 * <p>The registry is code today (one spec per point); a Learning Contract per
 * tenant is the next step and will read the same names.
 */
@Component
public class DecisionPoints {

    private static final Logger log = LoggerFactory.getLogger(DecisionPoints.class);
    public static final String SOURCE = "campaign";

    public static final String JOURNEY_ENROLMENT = "journey.enrolment";
    public static final String CAMPAIGN_TREATMENT = "campaign.treatment";
    public static final String JOURNEY_NEXT_BEST_ACTION = "journey.nextBestAction";
    public static final String JOURNEY_ARM_WEIGHTS = "journey.armWeights";

    private final DomainEventPublisher events;
    private final ContractProvider contracts;
    private final Map<String, DecisionPointSpec> registry = new LinkedHashMap<>();

    public DecisionPoints(DomainEventPublisher events, ContractProvider contracts) {
        this.events = events;
        this.contracts = contracts;
        register(new DecisionPointSpec(JOURNEY_ENROLMENT, "party", "high",
                "a customer enters a journey: holdout, or which message variant (arm)",
                new HoldoutThenWeightedHashPolicy()));
        register(new DecisionPointSpec(CAMPAIGN_TREATMENT, "party", "high",
                "a campaign reaches a customer: holdout, or which A/B arm",
                new HoldoutThenWeightedHashPolicy()));
        register(new DecisionPointSpec(JOURNEY_NEXT_BEST_ACTION, "party", "medium",
                "two journeys want the same customer this tick: which one speaks",
                new PriorityFirstPolicy()));
        register(new DecisionPointSpec(JOURNEY_ARM_WEIGHTS, "journey", "medium",
                "the tuner judges a journey's arms: shift traffic, hold, or wait for evidence",
                new ZThresholdTunerPolicy()));
    }

    public void register(DecisionPointSpec spec) {
        registry.put(spec.name(), spec);
    }

    public DecisionPointSpec spec(String decisionPoint) {
        DecisionPointSpec spec = registry.get(decisionPoint);
        if (spec == null) {
            throw new IllegalArgumentException("unknown decision point " + decisionPoint);
        }
        return spec;
    }

    public List<Map<String, Object>> registryView() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DecisionPointSpec s : registry.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("subjectType", s.subjectType());
            m.put("autonomy", s.autonomy());
            m.put("description", s.description());
            m.put("policy", s.policy().name());
            m.put("policyVersion", s.policy().version());
            m.put("source", SOURCE);
            m.put("@type", "DecisionPoint");
            out.add(m);
        }
        return out;
    }

    /**
     * Decide with the point's registered policy. {@code fallbackAction} answers
     * when no action survives the constraints or the policy fails; it may be
     * null, in which case the record says so and the caller handles "nothing".
     */
    public DecisionRecord decide(String decisionPoint, String subjectId, Map<String, Object> context,
            List<String> candidates, List<Constraint> constraints, String fallbackAction) {
        DecisionPointSpec spec = spec(decisionPoint);
        return decide(spec, spec.policy(), subjectId, context, candidates, constraints, fallbackAction);
    }

    public DecisionRecord decide(DecisionPointSpec spec, DecisionPolicy policy, String subjectId,
            Map<String, Object> context, List<String> candidates, List<Constraint> constraints,
            String fallbackAction) {
        return run(spec, policy, subjectId, context, candidates, constraints, fallbackAction, true);
    }

    /** What WOULD be decided — same seam, nothing recorded, nothing published (the contract dry-run). */
    public DecisionRecord preview(String decisionPoint, String subjectId, Map<String, Object> context,
            List<String> candidates, String fallbackAction) {
        DecisionPointSpec spec = spec(decisionPoint);
        return run(spec, spec.policy(), subjectId, context, candidates, List.of(), fallbackAction, false);
    }

    private DecisionRecord run(DecisionPointSpec spec, DecisionPolicy policy, String subjectId,
            Map<String, Object> context, List<String> candidates, List<Constraint> constraints,
            String fallbackAction, boolean record) {
        // THE LEARNING CONTRACT: the tenant's intent for this point, applied
        // before the policy — allowed actions, exploration cap, autonomy,
        // fallback, and the pause switch. Every effect is a named constraint line.
        Contract contract = contracts == null ? null : contracts.contractFor(spec.name()).orElse(null);
        List<Constraint> all = new ArrayList<>(constraints == null ? List.<Constraint>of() : constraints);
        List<String> fired = new ArrayList<>();
        Map<String, Object> ctx = new LinkedHashMap<>(context == null ? Map.of() : context);
        String fallback = fallbackAction;
        String autonomy = spec.autonomy();
        if (contract != null) {
            if (contract.fallbackAction() != null && !contract.fallbackAction().isBlank()) {
                fallback = contract.fallbackAction();
            }
            if (contract.autonomy() != null && !contract.autonomy().isBlank()) {
                autonomy = contract.autonomy();
            }
            if (contract.allowedActions() != null) {
                all.add(0, new AllowedActionsConstraint(contract.allowedActions()));
            }
            if (contract.explorationMaxPercent() != null && ctx.get("holdoutPercent") instanceof Number h
                    && h.intValue() > contract.explorationMaxPercent()) {
                ctx.put("holdoutPercent", contract.explorationMaxPercent());
                fired.add("learning-contract: holdout capped at " + contract.explorationMaxPercent()
                        + " % (asked " + h.intValue() + " %)");
            }
        }
        List<String> eligible = new ArrayList<>();
        DecisionRequest probe = new DecisionRequest(spec.name(), spec.subjectType(), subjectId, ctx, candidates);
        for (String action : candidates) {
            boolean keep = true;
            for (Constraint c : all) {
                Optional<String> why = c.reject(action, probe);
                if (why.isPresent()) {
                    fired.add(c.name() + ": " + action + " — " + why.get());
                    keep = false;
                    break;
                }
            }
            if (keep) {
                eligible.add(action);
            }
        }
        DecisionRequest request = new DecisionRequest(spec.name(), spec.subjectType(), subjectId, ctx, eligible);
        Decision decision;
        boolean fellBack = false;
        if (contract != null && !contract.enabled()) {
            decision = Decision.deterministic(fallback, "learning contract paused — fallback answers", Map.of());
            fellBack = true;
            fired.add("learning-contract: paused");
        } else if (eligible.isEmpty()) {
            decision = Decision.deterministic(fallback, "no eligible action — fallback", Map.of());
            fellBack = true;
        } else {
            try {
                decision = policy.decide(request);
                if (decision == null || decision.action() == null || !eligible.contains(decision.action())) {
                    decision = Decision.deterministic(fallback,
                            "policy answered outside the eligible set — fallback", Map.of());
                    fellBack = true;
                }
            } catch (RuntimeException e) {
                log.warn("decision point {} policy {} failed: {} — fallback '{}'", spec.name(), policy.name(),
                        e.getMessage(), fallback);
                decision = Decision.deterministic(fallback, "policy failed: " + e.getMessage(), Map.of());
                fellBack = true;
            }
        }
        DecisionRecord out = new DecisionRecord(UUID.randomUUID().toString(), spec.name(), spec.subjectType(),
                subjectId, List.copyOf(candidates), eligible, fired, decision.action(), decision.propensity(),
                policy.name(), policy.version(), decision.reason(), request.context(), decision.evidence(),
                autonomy, fellBack, SOURCE, OffsetDateTime.now(), contract == null ? null : contract.ref());
        if (record) {
            publish(out);
        }
        return out;
    }

    /** The contract's allowed-action list as a constraint, so the receipt names it like any other rule. */
    static final class AllowedActionsConstraint implements Constraint {
        private final List<String> allowed;

        AllowedActionsConstraint(List<String> allowed) {
            this.allowed = allowed;
        }

        public String name() {
            return "learning-contract";
        }

        public Optional<String> reject(String action, DecisionRequest request) {
            return allowed.contains(action) ? Optional.empty() : Optional.of("not in the contract's allowed actions");
        }
    }

    private void publish(DecisionRecord record) {
        try {
            events.publish("DecisionRecordedEvent", "decision", record.toMap());
        } catch (RuntimeException e) {
            // the decision stands; the log is at-least-once via the outbox, never a reason to fail the choice
            log.warn("decision {} not published: {}", record.decisionId(), e.getMessage());
        }
    }

    /** The outcome that followed a decision, joined by id: a conversion, an adoption, a churn. */
    public void outcome(String decisionId, String outcome, Object value) {
        if (decisionId == null || decisionId.isBlank()) {
            return; // rows from before the log existed — nothing to attribute
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decisionId", decisionId);
        body.put("outcome", outcome);
        if (value != null) {
            body.put("value", value);
        }
        body.put("observedAt", OffsetDateTime.now().toString());
        body.put("@type", "DecisionOutcome");
        try {
            events.publish("DecisionOutcomeEvent", "decisionOutcome", body);
        } catch (RuntimeException e) {
            log.warn("outcome for decision {} not published: {}", decisionId, e.getMessage());
        }
    }
}
