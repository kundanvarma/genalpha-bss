package com.bss.campaign.service;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.client.CommunicationClient;
import com.bss.campaign.client.InsightClient;
import com.bss.campaign.client.SegmentMember;
import com.bss.campaign.dto.ArbitrationDecisionView;
import com.bss.campaign.dto.ArmRow;
import com.bss.campaign.dto.ConversionReceipt;
import com.bss.campaign.dto.Conversions;
import com.bss.campaign.dto.EnrollmentReceipt;
import com.bss.campaign.dto.JourneyRequest;
import com.bss.campaign.dto.JourneyStats;
import com.bss.campaign.dto.JourneyView;
import com.bss.campaign.dto.SegmentEnrollmentReceipt;
import com.bss.campaign.dto.TuneEntry;
import com.bss.campaign.dto.TuneResult;
import com.bss.campaign.entity.Journey;
import com.bss.campaign.entity.JourneyEnrollment;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.exception.NotFoundException;
import com.bss.campaign.repository.JourneyEnrollmentRepository;
import com.bss.campaign.repository.JourneyRepository;
import com.bss.campaign.security.TenantContext;
import com.bss.campaign.security.TenantRegistry;
import com.bss.campaign.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.bss.campaign.api.Wire.textOf;

/**
 * The journey engine: sequences as DATA. Steps are a list — message, wait —
 * the console edits; the conversion event is the ALWAYS-ON EXIT RULE (a
 * converter leaves from any step); holdouts walk the same ledger without
 * ever hearing a message, so journey lift reads like campaign lift. The
 * guardrails Braze's users forgot are the model here: once-per-customer
 * enrollment, pause stops the clock, nothing fires without an active
 * journey.
 */
@Service
public class JourneyService {

    private static final Logger log = LoggerFactory.getLogger(JourneyService.class);
    private static final TypeReference<List<Map<String, Object>>> STEP_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<TuneEntry>> TUNE_LOG = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JourneyRepository journeys;
    private final JourneyEnrollmentRepository enrollments;
    private final CommunicationClient communication;
    private final InsightClient insight;
    private final TenantScope tenantScope;
    private final TenantRegistry tenants;
    private final ObjectMapper objectMapper;
    private final FrequencyGuard frequency;
    private final com.bss.campaign.client.CatalogClient catalog;
    private final com.bss.campaign.tick.TickGuard tickGuard;
    /** Auto-tuning: an arm needs this many treated enrolments before it may be judged. */
    @org.springframework.beans.factory.annotation.Value("${bss.campaign.journey-tune-min-per-arm:20}")
    private int tuneMinPerArm = 20;
    /** The traffic floor every arm keeps, so the desk keeps learning. */
    @org.springframework.beans.factory.annotation.Value("${bss.campaign.journey-tune-floor-percent:10}")
    private int tuneFloorPercent = 10;
    /** One-sided z at which a difference counts as evidence (1.64 ≈ 95 %). */
    @org.springframework.beans.factory.annotation.Value("${bss.campaign.journey-tune-z:1.64}")
    private double tuneZ = 1.64;
    private final com.bss.campaign.repository.ArbitrationDecisionRepository arbitration;
    private final com.bss.campaign.decision.DecisionPoints decisions;

    public JourneyService(JourneyRepository journeys, JourneyEnrollmentRepository enrollments,
            CommunicationClient communication, InsightClient insight,
            TenantScope tenantScope, TenantRegistry tenants, ObjectMapper objectMapper,
            FrequencyGuard frequency, com.bss.campaign.client.CatalogClient catalog,
            com.bss.campaign.tick.TickGuard tickGuard,
            com.bss.campaign.repository.ArbitrationDecisionRepository arbitration,
            com.bss.campaign.decision.DecisionPoints decisions) {
        this.decisions = decisions;
        this.journeys = journeys;
        this.enrollments = enrollments;
        this.communication = communication;
        this.insight = insight;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.objectMapper = objectMapper;
        this.frequency = frequency;
        this.catalog = catalog;
        this.tickGuard = tickGuard;
        this.arbitration = arbitration;
    }

    // ---------------- authoring ----------------

    @Transactional
    public JourneyView create(JourneyRequest dto) {
        if (dto.name() == null || absent(dto.steps())) {
            throw new BadRequestException("name and steps are required");
        }
        List<Map<String, Object>> steps = parseSteps(dto.steps());
        validateSteps(steps);
        Journey entity = new Journey();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/journey/" + id);
        entity.setName(dto.name());
        entity.setStatus(dto.status() == null ? Journey.ACTIVE : requireLifecycle(dto.status()));
        entity.setTriggerEventType(JourneyRequest.text(dto.triggerEventType()));
        entity.setTriggerState(JourneyRequest.text(dto.triggerState()));
        entity.setSegmentName(JourneyRequest.text(dto.segmentName()));
        entity.setConversionEvent(JourneyRequest.text(dto.conversionEvent()));
        if (dto.holdoutPercent() != null) {
            entity.setHoldoutPercent(requireHoldout(dto.holdoutPercent()));
        }
        if (dto.category() != null) {
            entity.setCategory(requireCategory(dto.category()));
        }
        applyArms(entity, dto.armsDocument(), true);
        if (dto.autoTune() != null) {
            entity.setAutoTune(dto.autoTune());
        }
        if (dto.priority() != null) {
            entity.setPriority(dto.priority());
        }
        entity.setSteps(serializeSteps(steps));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return view(journeys.save(entity));
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    /** One rulebook for steps, shared by create and edit. */
    private void validateSteps(List<Map<String, Object>> steps) {
        if (steps.isEmpty()) {
            throw new BadRequestException("a journey needs at least one step");
        }
        for (Map<String, Object> step : steps) {
            String type = String.valueOf(step.get("type"));
            if ("message".equals(type)) {
                // a message is either inline (subject+content) or a templateRef
                if (step.get("templateRef") == null
                        && (step.get("subject") == null || step.get("content") == null)) {
                    throw new BadRequestException("message steps need subject and content, or a templateRef");
                }
            } else if ("wait".equals(type)) {
                if (waitSeconds(step) <= 0) {
                    throw new BadRequestException("wait steps need seconds/minutes/hours/days > 0");
                }
            } else if ("waitForEvent".equals(type)) {
                if (step.get("event") == null || String.valueOf(step.get("event")).isBlank()) {
                    throw new BadRequestException("waitForEvent steps need an event to wait for");
                }
            } else if ("exit".equals(type)) {
                // a bare goal/exit node — no fields required
                continue;
            } else if ("branch".equals(type) || "decision".equals(type)) {
                // the journey READS the customer before speaking: an insight
                // segment decides which of two messages (either may be
                // omitted = say nothing on that side)
                if (step.get("inSegment") == null || String.valueOf(step.get("inSegment")).isBlank()) {
                    throw new BadRequestException("decision/branch steps need inSegment (an insight segment name)");
                }
                boolean anyPath = false;
                for (String path : List.of("then", "else")) {
                    if (step.get(path) == null) {
                        continue;
                    }
                    if (!(step.get(path) instanceof Map<?, ?> m)
                            || (m.get("templateRef") == null
                                && (m.get("subject") == null || m.get("content") == null))) {
                        throw new BadRequestException(
                                "branch '" + path + "' must be a message {subject, content} or {templateRef}");
                    }
                    anyPath = true;
                }
                // a decision is valid if it says something (then/else message) OR
                // routes somewhere (thenNext/elseNext to another node's id)
                if (!anyPath && step.get("thenNext") == null && step.get("elseNext") == null) {
                    throw new BadRequestException(
                            "a decision needs a then/else message or a thenNext/elseNext route");
                }
            } else {
                throw new BadRequestException("step type must be 'message', 'wait', 'decision'/'branch', "
                        + "'waitForEvent' or 'exit'");
            }
        }
    }

    private String requireCategory(String value) {
        String v = value.trim().toLowerCase();
        if (!Journey.MARKETING.equals(v) && !Journey.TRANSACTIONAL.equals(v)) {
            throw new BadRequestException("category must be 'marketing' or 'transactional'");
        }
        return v;
    }

    private int requireHoldout(int holdout) {
        if (holdout < 0 || holdout > 90) {
            throw new BadRequestException("holdoutPercent must be 0-90");
        }
        return holdout;
    }

    private String serializeSteps(List<Map<String, Object>> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("steps are not serializable JSON");
        }
    }

    @Transactional(readOnly = true)
    public List<JourneyView> findAll() {
        // archived journeys are a soft delete: hidden from the default list
        return journeys.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(j -> !Journey.ARCHIVED.equals(j.getStatus()))
                .map(this::view).toList();
    }

    /**
     * Live edit, the way marketers expect it (the Klaviyo/Customer.io model):
     * changes take effect FORWARD-ONLY. The tick re-reads steps on every run,
     * so people parked mid-journey get the new copy at their next send; nobody
     * is re-sent a step they already passed, and if the journey shrinks below
     * someone's position they simply complete. Steps are re-validated with the
     * same rulebook as create, and a steps edit is stamped (stepsEditedAt) so
     * lift/funnel reads stay honest about mixing step versions.
     */
    @Transactional
    public JourneyView patch(String id, JourneyRequest patch) {
        Journey entity = journeys.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", id));
        if (patch.status() != null) {
            entity.setStatus(requireLifecycle(patch.status()));
        }
        if (patch.priority() != null) {
            entity.setPriority(patch.priority());
        }
        if (patch.name() != null) {
            entity.setName(patch.name());
        }
        // trigger/segment/conversion edits govern FUTURE enrollment and exits;
        // people already in flight continue their journey (Customer.io rule) —
        // a key that is present, even as null, is an edit; an absent key is not
        if (patch.triggerEventType() != null) {
            entity.setTriggerEventType(JourneyRequest.text(patch.triggerEventType()));
        }
        if (patch.triggerState() != null) {
            entity.setTriggerState(JourneyRequest.text(patch.triggerState()));
        }
        if (patch.segmentName() != null) {
            entity.setSegmentName(JourneyRequest.text(patch.segmentName()));
        }
        if (patch.conversionEvent() != null) {
            entity.setConversionEvent(JourneyRequest.text(patch.conversionEvent()));
        }
        // variants are stamped at enrollment, so a holdout change only
        // buckets NEW entrants — per-variant lift math stays valid
        if (patch.mentionsArms()) {
            applyArms(entity, patch.armsDocument(), true);
        }
        if (patch.autoTune() != null) {
            entity.setAutoTune(patch.autoTune());
        }
        if (patch.holdoutPercent() != null) {
            entity.setHoldoutPercent(requireHoldout(patch.holdoutPercent()));
        }
        if (patch.category() != null) {
            entity.setCategory(requireCategory(patch.category()));
        }
        if (!absent(patch.steps())) {
            List<Map<String, Object>> steps = parseSteps(patch.steps());
            validateSteps(steps);
            String serialized = serializeSteps(steps);
            if (!serialized.equals(entity.getSteps())) {
                entity.setSteps(serialized);
                entity.setStepsEditedAt(OffsetDateTime.now());
            }
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return view(journeys.save(entity));
    }

    /** Delete a journey and its enrollment ledger. */
    @Transactional
    public void delete(String id) {
        Journey entity = journeys.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", id));
        enrollments.deleteByTenantIdAndJourneyId(tenantScope.currentTenantId(), id);
        journeys.delete(entity);
    }

    private String requireLifecycle(String value) {
        if (!Journey.LIFECYCLE.contains(value)) {
            throw new BadRequestException("status must be one of " + Journey.LIFECYCLE);
        }
        return value;
    }

    // ---------------- enrollment ----------------

    /** Segment enrollment: everyone insight puts in the segment, once. */
    @Transactional
    public SegmentEnrollmentReceipt enrollSegment(String journeyId) {
        Journey journey = journeys.findByIdAndTenantId(journeyId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", journeyId));
        if (journey.getSegmentName() == null || journey.getSegmentName().isBlank()) {
            throw new BadRequestException("this journey has no segment — it enrolls on events");
        }
        if (!Journey.ACTIVE.equals(journey.getStatus())) {
            throw new BadRequestException("only an active journey can enroll");
        }
        int enrolled = 0;
        for (SegmentMember member : insight.segmentMembers(journey.getSegmentName())) {
            if (enroll(journey, String.valueOf(member.partyId()), java.util.Map.of())) {
                enrolled++;
            }
        }
        return new SegmentEnrollmentReceipt(journeyId, journey.getSegmentName(), enrolled);
    }

    /** Event entry: business events enroll customers into matching journeys. */
    @Transactional
    public void onEvent(String eventType, String state, String partyId,
            List<String> offeringIds) {
        onEvent(eventType, state, partyId, offeringIds, java.util.Map.of());
    }

    public void onEvent(String eventType, String state, String partyId,
            List<String> offeringIds, Map<String, Object> context) {
        String tenant = tenantScope.currentTenantId();
        for (Journey journey : journeys.findByTenantIdAndStatusAndTriggerEventType(
                tenant, Journey.ACTIVE, eventType)) {
            if (journey.getTriggerState() != null && !journey.getTriggerState().equals(state)) {
                continue;
            }
            enroll(journey, partyId, context);
        }
        // the always-on exit rule: a matching conversion event converts every
        // ACTIVE enrollment whose journey names it — out from any step
        java.math.BigDecimal value = null; // catalog is asked once, and only on a match
        for (JourneyEnrollment enrollment
                : enrollments.findByTenantIdAndPartyIdAndStatus(tenant, partyId, "active")) {
            Journey journey = journeys.findByIdAndTenantId(enrollment.getJourneyId(), tenant).orElse(null);
            if (journey == null) {
                continue;
            }
            String wanted = journey.getConversionEvent() == null || journey.getConversionEvent().isBlank()
                    ? "ProductOrderStateChangeEvent:completed" : journey.getConversionEvent();
            String[] parts = wanted.split(":", 2);
            if (parts[0].equals(eventType) && (parts.length < 2 || parts[1].equals(state))) {
                if (value == null) {
                    value = catalog.monthlyValueOf(tenant, offeringIds);
                }
                enrollment.setStatus("converted");
                enrollment.setConvertedAt(OffsetDateTime.now());
                enrollment.setConversionValue(value);
                enrollments.save(enrollment);
                decisions.outcome(enrollment.getDecisionId(), "conversion", value);
                log.info("journey '{}' conversion: party {} exited from step {} ({}) worth {}/month",
                        journey.getName(), partyId, enrollment.getStepIndex(),
                        enrollment.getVariant(), value);
            }
        }
        // waitForEvent nodes: an enrollment parked on this event advances now
        // (conversion above already ran, so a converted enrollment is skipped)
        for (JourneyEnrollment enrollment
                : enrollments.findByTenantIdAndPartyIdAndStatus(tenant, partyId, "active")) {
            if (enrollment.getAwaitEvent() == null) {
                continue;
            }
            String[] want = enrollment.getAwaitEvent().split(":", 2);
            if (want[0].equals(eventType) && (want.length < 2 || want[1].equals(state))) {
                Journey journey = journeys.findByIdAndTenantId(enrollment.getJourneyId(), tenant).orElse(null);
                if (journey == null || !Journey.ACTIVE.equals(journey.getStatus())) {
                    continue;
                }
                enrollment.setAwaitEvent(null);
                // follow the waitForEvent node's out-edge (an explicit 'next' or the next node)
                List<Map<String, Object>> steps = parseSteps(journey.getSteps());
                Map<String, Integer> ids = idIndex(steps);
                int i = enrollment.getStepIndex();
                enrollment.setStepIndex(i >= 0 && i < steps.size() ? nextIndex(steps.get(i), ids, i) : i + 1);
                advance(journey, enrollment);
            }
        }
    }

    private boolean enroll(Journey journey, String partyId, Map<String, Object> context) {
        String tenant = tenantScope.currentTenantId();
        if (enrollments.existsByTenantIdAndJourneyIdAndPartyId(tenant, journey.getId(), partyId)) {
            return false;
        }
        // THE DECISION: holdout, or which arm — one choice, one record, one id;
        // the conversion later joins back to it. Same hashes as always, so a
        // customer keeps the bucket they were dealt before the log existed.
        List<Map<String, Object>> arms = armsOf(journey);
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add("holdout");
        if (arms.isEmpty()) {
            candidates.add("message");
        } else {
            arms.forEach(a -> candidates.add(str(a.get("name"))));
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("seed", journey.getId());
        ctx.put("journeyId", journey.getId());
        ctx.put("partyId", partyId);
        ctx.put("holdoutPercent", journey.getHoldoutPercent());
        if (!arms.isEmpty()) {
            ctx.put("weights", weightsOf(journey, arms));
        }
        com.bss.campaign.decision.DecisionRecord dealt = decisions.decide(
                com.bss.campaign.decision.DecisionPoints.JOURNEY_ENROLMENT, partyId, ctx, candidates,
                List.of(), "holdout");
        boolean holdout = "holdout".equals(dealt.action());
        JourneyEnrollment enrollment = new JourneyEnrollment();
        enrollment.setId(UUID.randomUUID().toString());
        enrollment.setTenantId(tenant);
        enrollment.setJourneyId(journey.getId());
        enrollment.setPartyId(partyId);
        enrollment.setVariant(holdout ? "holdout" : "treated");
        enrollment.setDecisionId(dealt.decisionId());
        if (!holdout && !arms.isEmpty()) {
            enrollment.setArm(dealt.action());
        }
        enrollment.setEnrolledAt(OffsetDateTime.now());
        enrollment.setNextActionAt(OffsetDateTime.now());
        if (context != null && !context.isEmpty()) {
            try { enrollment.setContextJson(objectMapper.writeValueAsString(context)); } catch (Exception ignore) { /* best-effort */ }
        }
        try {
            enrollments.save(enrollment);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    // ---------------- the tick ----------------

    /** Walk everyone whose next step is due — per tenant, RLS-honest. */
    @Scheduled(fixedDelayString = "${bss.campaign.journey-tick-ms:5000}")
    public void tick() {
        if (!tickGuard.claim("journey", java.time.Duration.ofSeconds(60))) {
            return; // another replica is walking the journeys — one send, never two
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    tickTenant(tenant.getId());
                } catch (Exception e) {
                    log.warn("journey tick skipped tenant '{}': {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("journey");
        }
    }

    @Transactional
    public void tickTenant(String tenantId) {
        // Oldest-due first: if a backlog ever exceeds the batch, the batch is
        // the LONGEST-waiting 200, not an arbitrary heap-order 200 — a fresh
        // enrollment can wait a tick, but nobody waits forever.
        List<JourneyEnrollment> due = enrollments
                .findTop200ByTenantIdAndStatusAndNextActionAtBeforeOrderByNextActionAtAsc(
                        tenantId, "active", OffsetDateTime.now());
        // Resolve each enrollment's journey once, dropping the dead/paused ones.
        Map<String, Journey> byId = new LinkedHashMap<>();
        List<JourneyEnrollment> live = new java.util.ArrayList<>();
        for (JourneyEnrollment e : due) {
            Journey j = byId.computeIfAbsent(e.getJourneyId(),
                    id -> journeys.findByIdAndTenantId(id, tenantId).orElse(null));
            if (j != null && Journey.ACTIVE.equals(j.getStatus())) {
                live.add(e);
            } else {
                // a paused/deleted journey's enrollments must leave the due set,
                // or they fill every batch forever and starve fresh enrollments
                // (the 200-row batch was 998 dead rows deep on a long-lived dev
                // tenant). Re-checked hourly, so a resumed journey picks them up.
                e.setNextActionAt(OffsetDateTime.now().plusHours(1));
                enrollments.save(e);
            }
        }
        // NBA arbitration: process highest-priority journeys first, so the best
        // action per customer runs; a second message to the same customer in
        // this same tick is HELD, and the decision is logged with its reason.
        live.sort((a, b) -> Integer.compare(byId.get(b.getJourneyId()).getPriority(),
                byId.get(a.getJourneyId()).getPriority()));
        Map<String, String> wonBy = new java.util.HashMap<>(); // partyId -> winning journeyId
        for (JourneyEnrollment enrollment : live) {
            Journey journey = byId.get(enrollment.getJourneyId());
            // Arbitration is opt-in: only journeys given a priority (> 0) compete
            // for the customer's single best action. A priority-0 journey is
            // always-on and never held — a human enrols a journey into the NBA
            // pool by assigning it a priority.
            if (journey.getPriority() > 0 && isSendingNode(journey, enrollment)) {
                String winner = wonBy.get(enrollment.getPartyId());
                if (winner != null && !winner.equals(enrollment.getJourneyId())) {
                    holdForArbitration(tenantId, journey, enrollment, byId.get(winner));
                    continue;
                }
                wonBy.put(enrollment.getPartyId(), enrollment.getJourneyId());
            }
            advance(journey, enrollment);
        }
    }

    /** True when the enrollment's next node actually sends a message (and so
     *  competes for the customer's attention this tick). */
    private boolean isSendingNode(Journey journey, JourneyEnrollment enrollment) {
        List<Map<String, Object>> steps = parseSteps(journey.getSteps());
        int i = enrollment.getStepIndex();
        if (i < 0 || i >= steps.size()) {
            return false;
        }
        String type = String.valueOf(steps.get(i).get("type"));
        return "message".equals(type) || "branch".equals(type) || "decision".equals(type);
    }

    /** Hold the losing journey a short while and record the NBA decision. */
    private void holdForArbitration(String tenantId, Journey held, JourneyEnrollment enrollment,
            Journey winner) {
        enrollment.setNextActionAt(OffsetDateTime.now().plusSeconds(3600));
        enrollments.save(enrollment);
        // the choice itself goes through the seam: candidates are the two
        // journeys, the policy says which speaks, the record carries the priorities
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("partyId", enrollment.getPartyId());
        Map<String, Integer> priorities = new LinkedHashMap<>();
        if (winner != null) {
            priorities.put(winner.getId(), winner.getPriority());
        }
        priorities.put(held.getId(), held.getPriority());
        ctx.put("priorities", priorities);
        List<String> candidates = new java.util.ArrayList<>(priorities.keySet());
        com.bss.campaign.decision.DecisionRecord nba = decisions.decide(
                com.bss.campaign.decision.DecisionPoints.JOURNEY_NEXT_BEST_ACTION, enrollment.getPartyId(),
                ctx, candidates, List.of(), winner == null ? held.getId() : winner.getId());
        com.bss.campaign.entity.ArbitrationDecision d = new com.bss.campaign.entity.ArbitrationDecision();
        d.setId(UUID.randomUUID().toString());
        d.setTenantId(tenantId);
        d.setPartyId(enrollment.getPartyId());
        d.setDecisionId(nba.decisionId());
        d.setWinnerJourneyId(winner == null ? null : winner.getId());
        d.setHeldJourneyId(held.getId());
        d.setReason("held '" + held.getName() + "' (priority " + held.getPriority() + ") — '"
                + (winner == null ? "?" : winner.getName()) + "' (priority "
                + (winner == null ? "?" : winner.getPriority()) + ") is the next-best-action this moment");
        d.setDecidedAt(OffsetDateTime.now());
        arbitration.save(d);
        log.info("NBA: party {} — held '{}' for '{}'", enrollment.getPartyId(),
                held.getName(), winner == null ? "?" : winner.getName());
    }

    @Transactional(readOnly = true)
    public List<ArbitrationDecisionView> arbitrationDecisions(String partyId) {
        List<com.bss.campaign.entity.ArbitrationDecision> rows = partyId == null
                ? arbitration.findTop200ByTenantIdOrderByDecidedAtDesc(tenantScope.currentTenantId())
                : arbitration.findByTenantIdAndPartyIdOrderByDecidedAtDesc(tenantScope.currentTenantId(), partyId);
        return rows.stream().map(d -> new ArbitrationDecisionView(d.getPartyId(), d.getWinnerJourneyId(),
                d.getHeldJourneyId(), d.getReason(), d.getDecidedAt(), d.getDecisionId())).toList();
    }

    /** Run steps from where they stand until a wait parks them or the end. */
    private void advance(Journey journey, JourneyEnrollment enrollment) {
        List<Map<String, Object>> steps = parseSteps(journey.getSteps());
        Map<String, Integer> ids = idIndex(steps);
        int index = enrollment.getStepIndex();
        int guard = 0; // a mis-wired graph can cycle; bound one advance pass
        while (index >= 0 && index < steps.size() && guard++ < 500) {
            Map<String, Object> step = steps.get(index);
            String type = String.valueOf(step.get("type"));
            if ("message".equals(type)) {
                Map<String, Object> spoken = index == firstMessageIndex(steps) ? withArm(journey, enrollment, step) : step;
                if (!"holdout".equals(enrollment.getVariant()) && !sendGuarded(journey, enrollment, spoken)) {
                    return; // parked by quiet hours or the frequency cap
                }
                index = nextIndex(step, ids, index);
            } else if ("branch".equals(type) || "decision".equals(type)) {
                // read the customer ONCE — it picks the inline message AND the route
                boolean member = inSegment(journey, enrollment, step);
                Object chosen = member ? step.get("then") : step.get("else");
                Map<String, Object> message = chosen instanceof Map<?, ?> m ? castMessage(m) : null;
                if (message != null && !"holdout".equals(enrollment.getVariant())
                        && !sendGuarded(journey, enrollment, message)) {
                    return;
                }
                // TRUE branching: a decision can route each side to a different node
                Object route = member ? step.get("thenNext") : step.get("elseNext");
                index = route != null && ids.containsKey(String.valueOf(route))
                        ? ids.get(String.valueOf(route)) : nextIndex(step, ids, index);
            } else if ("exit".equals(type)) {
                enrollment.setStepIndex(index + 1);
                enrollment.setStatus("completed");
                enrollment.setNextActionAt(null);
                enrollment.setAwaitEvent(null);
                enrollments.save(enrollment);
                return;
            } else if ("waitForEvent".equals(type)) {
                if (enrollment.getAwaitEvent() == null) {
                    String await = String.valueOf(step.get("event"))
                            + (step.get("state") != null ? ":" + step.get("state") : "");
                    long timeout = waitSeconds(step);
                    enrollment.setAwaitEvent(await);
                    enrollment.setStepIndex(index);
                    enrollment.setNextActionAt(timeout > 0 ? OffsetDateTime.now().plusSeconds(timeout) : null);
                    enrollments.save(enrollment);
                    return;
                }
                enrollment.setAwaitEvent(null);
                if (step.get("onTimeout") instanceof Map<?, ?> m && !"holdout".equals(enrollment.getVariant())
                        && !sendGuarded(journey, enrollment, castMessage(m))) {
                    return;
                }
                index = nextIndex(step, ids, index);
            } else { // wait
                int nx = nextIndex(step, ids, index);
                enrollment.setStepIndex(nx);
                enrollment.setNextActionAt(OffsetDateTime.now().plusSeconds(waitSeconds(step)));
                enrollments.save(enrollment);
                return;
            }
        }
        enrollment.setStepIndex(index < 0 ? steps.size() : index);
        enrollment.setStatus("completed");
        enrollment.setNextActionAt(null);
        enrollments.save(enrollment);
    }

    /** Nodes may carry an id; edges reference it. Absent ids just fall through. */
    private Map<String, Integer> idIndex(List<Map<String, Object>> steps) {
        Map<String, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Object id = steps.get(i).get("id");
            if (id != null) m.putIfAbsent(String.valueOf(id), i);
        }
        return m;
    }

    /** The default out-edge: an explicit 'next' id, else the following node. */
    private int nextIndex(Map<String, Object> step, Map<String, Integer> ids, int i) {
        Object nx = step.get("next");
        return nx != null && ids.containsKey(String.valueOf(nx)) ? ids.get(String.valueOf(nx)) : i + 1;
    }

    /** Read the customer against an insight segment; unreachable insight = 'else'. */
    private boolean inSegment(Journey journey, JourneyEnrollment enrollment, Map<String, Object> step) {
        String segment = String.valueOf(step.get("inSegment"));
        try {
            return insight.segmentMembers(segment).stream()
                    .anyMatch(m -> enrollment.getPartyId().equals(String.valueOf(m.partyId())));
        } catch (Exception e) {
            log.warn("journey '{}' decision on '{}' could not read insight — taking 'else': {}",
                    journey.getName(), segment, e.getMessage());
            return false;
        }
    }

    /**
     * BRANCH: the journey reads the customer before speaking — membership
     * in an insight segment picks the message (a missing side = silence).
     * An unreachable insight fails SAFE to the 'else' side: the generic
     * message beats a wrong one.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMessage(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** The furthest node an enrollment reached: a completed run passed every
     *  node; anyone else got as far as their current step. */
    private int effectiveStep(JourneyEnrollment e, int stepCount) {
        return "completed".equals(e.getStatus()) ? stepCount : e.getStepIndex();
    }

    /** The guarded send every journey message goes through: quiet hours
     * park to the window's end, a spent budget postpones an hour.
     * @return false when parked (the enrollment was saved with a new time). */
    private boolean sendGuarded(Journey journey, JourneyEnrollment enrollment,
            Map<String, Object> message) {
        // a TRANSACTIONAL journey is a service notice ("running low", "line
        // suspended"): quiet hours and the marketing budget are for marketing
        boolean serviceNotice = journey.isTransactional();
        java.util.Optional<OffsetDateTime> quiet = serviceNotice ? java.util.Optional.empty() : frequency.quietUntil();
        if (quiet.isPresent()) {
            enrollment.setNextActionAt(quiet.get());
            enrollments.save(enrollment);
            log.info("journey '{}' parked for party {} — quiet hours until {}",
                    journey.getName(), enrollment.getPartyId(), quiet.get());
            return false;
        }
        if (!serviceNotice && !frequency.canSend(enrollment.getPartyId())) {
            enrollment.setNextActionAt(OffsetDateTime.now().plusSeconds(3600));
            enrollments.save(enrollment);
            log.info("journey '{}' postponed for party {} — marketing budget spent",
                    journey.getName(), enrollment.getPartyId());
            return false;
        }
        // tokens captured from the triggering event ({{order.id}}, {{tracking.url}}…)
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        if (enrollment.getContextJson() != null) {
            try {
                Map<String, Object> saved = objectMapper.readValue(enrollment.getContextJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                context.putAll(saved);
            } catch (Exception ignore) { /* best-effort */ }
        }
        if (message.get("promotionCode") != null) context.put("promotion.code", message.get("promotionCode"));
        if (journey.getName() != null) context.put("source", journey.getName());
        if (serviceNotice) context.put("category", Journey.TRANSACTIONAL);
        com.bss.campaign.client.CommunicationClient.SendOutcome outcome;
        String templateRef = textOf(message, "templateRef");
        if (templateRef != null) {
            outcome = communication.sendTemplated(enrollment.getPartyId(), templateRef,
                    textOf(message, "locale"), textOf(message, "channel"),
                    context);
        } else {
            String content = String.valueOf(message.get("content"));
            if (message.get("promotionCode") != null) {
                content = content.replace("{code}", String.valueOf(message.get("promotionCode")));
            }
            outcome = communication.send(enrollment.getPartyId(),
                    java.util.Objects.requireNonNullElse(textOf(message, "subject"), ""),
                    content, textOf(message, "channel"), context);
        }
        // Communication has guardrails of its own (frequency cap, opt-out) and
        // declines with a 200 — the postpone-not-drop rule must hold HERE too,
        // or the step is silently lost and the enrollment walks on unmessaged.
        if (outcome == com.bss.campaign.client.CommunicationClient.SendOutcome.CAPPED) {
            enrollment.setNextActionAt(OffsetDateTime.now().plusSeconds(3600));
            enrollments.save(enrollment);
            log.info("journey '{}' postponed for party {} — communication's frequency cap",
                    journey.getName(), enrollment.getPartyId());
            return false;
        }
        if (outcome == com.bss.campaign.client.CommunicationClient.SendOutcome.SUPPRESSED) {
            // the customer opted out of marketing: say nothing, spend nothing,
            // and let them walk the rest of the journey in silence (retrying
            // an opt-out forever would just be a quieter way to spam)
            log.info("journey '{}' said nothing to party {} — marketing opt-out",
                    journey.getName(), enrollment.getPartyId());
            return true;
        }
        if (!serviceNotice) {
            frequency.record(enrollment.getPartyId(), "journey");
        }
        return true;
    }

    // ---------------- the funnel ----------------

    @Transactional(readOnly = true)
    public JourneyStats statsOf(String journeyId) {
        Journey journey = journeys.findByIdAndTenantId(journeyId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", journeyId));
        List<JourneyEnrollment> all =
                enrollments.findByTenantIdAndJourneyId(tenantScope.currentTenantId(), journeyId);
        long treated = all.stream().filter(e -> !"holdout".equals(e.getVariant())).count();
        long heldOut = all.size() - treated;
        long treatedConv = all.stream().filter(e -> !"holdout".equals(e.getVariant())
                && "converted".equals(e.getStatus())).count();
        long holdoutConv = all.stream().filter(e -> "holdout".equals(e.getVariant())
                && "converted".equals(e.getStatus())).count();
        Map<String, Long> atStep = new LinkedHashMap<>();
        all.stream().filter(e -> "active".equals(e.getStatus()))
                .forEach(e -> atStep.merge("step" + e.getStepIndex(), 1L, Long::sum));
        // Named-stage funnel: how many active enrollments sit in each named
        // stage ("Welcome" vs "Activate" vs "Day-7"), in step order. This is
        // what turns positional steps into a stage view a journey owner reads.
        List<Map<String, Object>> steps = parseSteps(journey.getSteps());
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            if (step.get("stage") != null) byStage.putIfAbsent(String.valueOf(step.get("stage")), 0L);
        }
        all.stream().filter(e -> "active".equals(e.getStatus())).forEach(e -> {
            int idx = e.getStepIndex();
            String stage = idx >= 0 && idx < steps.size() ? str(steps.get(idx).get("stage")) : null;
            if (stage != null) byStage.merge(stage, 1L, Long::sum);
        });
        // BB2 — Journey Insights: a per-node funnel. For each node, how many
        // enrollments REACHED it (are at or beyond it) and how many are ACTIVE
        // there right now. The drop between consecutive nodes is where people
        // fall out — the number a journey owner reads to find the leak.
        List<JourneyStats.FunnelNode> funnel = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            long reached = all.stream().filter(e -> effectiveStep(e, steps.size()) >= idx).count();
            long activeHere = all.stream()
                    .filter(e -> "active".equals(e.getStatus()) && e.getStepIndex() == idx).count();
            funnel.add(new JourneyStats.FunnelNode(idx, str(steps.get(idx).get("type")),
                    str(steps.get(idx).get("stage")), reached, activeHere));
        }
        List<Map<String, Object>> armDefs = armsOf(journey);
        boolean hasArms = !armDefs.isEmpty();
        Double treatedRate = treated == 0 ? null : (double) treatedConv / treated;
        Double holdoutRate = heldOut == 0 ? null : (double) holdoutConv / heldOut;
        // attributed revenue, per exposed customer (see the campaign readout)
        java.math.BigDecimal treatedRevenue = enrollmentRevenue(all, false);
        java.math.BigDecimal holdoutRevenue = enrollmentRevenue(all, true);
        JourneyStats.Revenue revenue = null;
        if (treatedRevenue.signum() != 0 || holdoutRevenue.signum() != 0) {
            revenue = new JourneyStats.Revenue(treatedRevenue, holdoutRevenue,
                    treated > 0 && heldOut > 0
                            ? perCustomer(treatedRevenue, treated).subtract(perCustomer(holdoutRevenue, heldOut))
                            : null,
                    "monthly recurring value of converting orders");
        }
        // honesty marker: an edited journey's funnel/lift mixes step versions
        boolean edited = journey.getStepsEditedAt() != null;
        return new JourneyStats(journeyId, all.size(), treated, heldOut, atStep, byStage, funnel,
                all.stream().filter(e -> "completed".equals(e.getStatus())).count(),
                new Conversions(treatedConv, holdoutConv),
                hasArms ? armRows(journey, armDefs, all) : null,
                hasArms ? journey.isAutoTune() : null,
                hasArms ? tuningLogOf(journey) : null,
                treatedRate == null ? null : Math.round(treatedRate * 1000) / 10.0,
                holdoutRate == null ? null : Math.round(holdoutRate * 1000) / 10.0,
                treatedRate == null || holdoutRate == null ? null
                        : Math.round((treatedRate - holdoutRate) * 1000) / 10.0,
                heldOut > 0 && heldOut < 5
                        ? "holdout under 5 people — the lift is an anecdote, not a measurement" : null,
                revenue,
                edited ? journey.getStepsEditedAt() : null,
                edited ? "steps were edited after launch — earlier enrollees walked a different version" : null);
    }

    private java.math.BigDecimal enrollmentRevenue(List<JourneyEnrollment> all, boolean holdout) {
        return all.stream()
                .filter(e -> holdout == "holdout".equals(e.getVariant()))
                .map(JourneyEnrollment::getConversionValue)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private java.math.BigDecimal perCustomer(java.math.BigDecimal total, long customers) {
        return total.divide(java.math.BigDecimal.valueOf(customers), 2,
                java.math.RoundingMode.HALF_UP);
    }

    // ---------------- helpers ----------------

    /** The stored steps column. */
    private List<Map<String, Object>> parseSteps(String steps) {
        try {
            return objectMapper.readValue(steps, STEP_LIST);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("steps must be a JSON array of {type, ...}");
        }
    }

    /** The author's steps document: a JSON array, or the same array as a string. */
    private List<Map<String, Object>> parseSteps(JsonNode steps) {
        try {
            if (steps.isArray()) {
                return objectMapper.convertValue(steps, STEP_LIST);
            }
            return objectMapper.readValue(steps.isTextual() ? steps.asText() : steps.toString(), STEP_LIST);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new BadRequestException("steps must be a JSON array of {type, ...}");
        }
    }

    private static long waitSeconds(Map<String, Object> step) {
        long seconds = 0;
        if (step.get("seconds") != null) seconds += Long.parseLong(String.valueOf(step.get("seconds")));
        if (step.get("minutes") != null) seconds += 60 * Long.parseLong(String.valueOf(step.get("minutes")));
        if (step.get("hours") != null) seconds += 3600 * Long.parseLong(String.valueOf(step.get("hours")));
        if (step.get("days") != null) seconds += 86400 * Long.parseLong(String.valueOf(step.get("days")));
        return seconds;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private JourneyView view(Journey j) {
        List<Map<String, Object>> arms = armsOf(j);
        boolean hasArms = !arms.isEmpty();
        JsonNode steps;
        try {
            steps = objectMapper.readTree(j.getSteps());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            steps = objectMapper.getNodeFactory().textNode(j.getSteps());
        }
        return new JourneyView(j.getId(), j.getHref(), j.getName(), j.getStatus(), j.getTriggerEventType(),
                j.getTriggerState(), j.getSegmentName(), j.getConversionEvent(), j.getHoldoutPercent(),
                j.getCategory(), j.getPriority(),
                hasArms ? objectMapper.valueToTree(arms) : null,
                hasArms ? j.isAutoTune() : null,
                hasArms ? weightsOf(j, arms) : null,
                hasArms ? tuningLogOf(j) : null,
                steps, j.getStepsEditedAt(), j.getLastUpdate(), "Journey");
    }

    // ---------------- A/B arms + auto-tuning ----------------

    /** Enrol a list of parties by hand (an offline list, a store's walk-ins, a test cohort). */
    @Transactional
    public EnrollmentReceipt enrollParties(String journeyId, List<String> partyIds, JsonNode context) {
        Journey journey = journeys.findByIdAndTenantId(journeyId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", journeyId));
        Map<String, Object> tokens = context != null && context.isObject()
                ? objectMapper.convertValue(context, OBJECT) : null;
        int n = 0;
        Map<String, String> dealt = new LinkedHashMap<>(); // who got which arm (holdout: "holdout")
        for (String p : partyIds == null ? List.<String>of() : partyIds) {
            if (p != null && !p.isBlank() && enroll(journey, p.trim(), tokens)) {
                n++;
                JourneyEnrollment e = enrollments.findByTenantIdAndJourneyId(journey.getTenantId(), journey.getId()).stream()
                        .filter(x -> p.trim().equals(x.getPartyId())).findFirst().orElse(null);
                if (e != null) {
                    dealt.put(p.trim(), "holdout".equals(e.getVariant()) ? "holdout" : (e.getArm() == null ? "" : e.getArm()));
                }
            }
        }
        return new EnrollmentReceipt(journeyId, n, dealt);
    }

    /** Record a conversion that did not arrive as an event (a store sale, a call-centre close). */
    @Transactional
    public ConversionReceipt recordConversion(String journeyId, String partyId, java.math.BigDecimal value) {
        String tenant = tenantScope.currentTenantId();
        journeys.findByIdAndTenantId(journeyId, tenant).orElseThrow(() -> NotFoundException.forResource("Journey", journeyId));
        if (partyId == null) {
            throw new BadRequestException("partyId is required");
        }
        JourneyEnrollment e = enrollments.findByTenantIdAndJourneyId(tenant, journeyId).stream()
                .filter(x -> partyId.equals(x.getPartyId())).findFirst().orElse(null);
        if (e == null) {
            throw new BadRequestException("party " + partyId + " is not enrolled in this journey");
        }
        if (!"converted".equals(e.getStatus())) {
            e.setStatus("converted");
            e.setConvertedAt(OffsetDateTime.now());
            e.setConversionValue(value);
            enrollments.save(e);
            decisions.outcome(e.getDecisionId(), "conversion", value);
        }
        return new ConversionReceipt(journeyId, partyId, e.getStatus(), e.getArm() == null ? "" : e.getArm());
    }

    /** The tuner, on demand: judge the arms and shift traffic if the evidence is there. */
    @Transactional
    public TuneResult tune(String journeyId) {
        Journey journey = journeys.findByIdAndTenantId(journeyId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", journeyId));
        return new TuneResult(tuneJourney(journey), journeyId);
    }

    /** The tuner, on a clock: every auto-tune journey of every tenant. */
    @Scheduled(fixedDelayString = "${bss.campaign.journey-tune-ms:600000}", initialDelayString = "${bss.campaign.journey-tune-ms:600000}")
    public void tuneTick() {
        if (!tickGuard.claim("journey-tune", java.time.Duration.ofSeconds(120))) {
            return;
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                    for (Journey j : journeys.findByTenantId(tenant.getId())) {
                        if (j.isAutoTune() && Journey.ACTIVE.equals(j.getStatus()) && armsOf(j).size() >= 2) {
                            tuneJourney(j);
                        }
                    }
                } catch (Exception e) {
                    log.warn("journey tuner skipped tenant '{}': {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("journey-tune");
        }
    }

    /**
     * The rule, in one place and in plain words: every arm keeps a floor of traffic;
     * an arm is judged only after it has enough treated enrolments; the best arm
     * takes the rest of the traffic only when its conversion rate beats the runner-up
     * with a one-sided z above the threshold. Otherwise nothing moves. Every call
     * writes a ledger entry — shift, hold, or waiting — with the numbers it saw.
     */
    private TuneEntry tuneJourney(Journey journey) {
        List<Map<String, Object>> arms = armsOf(journey);
        List<JourneyEnrollment> all = enrollments.findByTenantIdAndJourneyId(journey.getTenantId(), journey.getId());
        List<ArmRow> rows = armRows(journey, arms, all);
        Map<String, Integer> before = weightsOf(journey, arms);
        String at = OffsetDateTime.now().toString();
        // the rule lives in ZThresholdTunerPolicy behind the seam; this method
        // only feeds it the numbers (as the open context the policy reads) and
        // keeps the journey's ledger
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("journeyId", journey.getId());
        ctx.put("rows", objectMapper.convertValue(rows, STEP_LIST));
        ctx.put("before", before);
        ctx.put("minPerArm", tuneMinPerArm);
        ctx.put("floorPercent", tuneFloorPercent);
        ctx.put("threshold", tuneZ);
        com.bss.campaign.decision.DecisionRecord judged = decisions.decide(
                com.bss.campaign.decision.DecisionPoints.JOURNEY_ARM_WEIGHTS, journey.getId(), ctx,
                List.of("shift", "hold", "waiting"), List.of(), "hold");
        String decision = judged.action();
        Map<String, Integer> after = new LinkedHashMap<>(before);
        if (judged.evidence().get("after") instanceof Map<?, ?> a) {
            a.forEach((k, v) -> after.put(String.valueOf(k), ((Number) v).intValue()));
        }
        boolean judgedZ = judged.evidence().get("z") instanceof Number;
        TuneEntry entry = new TuneEntry(at, rows, before,
                judgedZ ? ((Number) judged.evidence().get("z")).doubleValue() : null,
                judgedZ ? tuneZ : null,
                judged.reason(), decision, after, judged.decisionId());
        if ("shift".equals(decision)) {
            try {
                journey.setArmWeights(objectMapper.writeValueAsString(after));
            } catch (Exception ignore) { /* keep the previous weights */ }
            log.info("journey '{}' tuned: {} → {} — {}", journey.getName(), before, after, entry.why());
        }
        List<TuneEntry> logRows = new java.util.ArrayList<>(tuningLogOf(journey));
        logRows.add(entry);
        while (logRows.size() > 30) {
            logRows.remove(0);
        }
        try {
            String encoded = objectMapper.writeValueAsString(logRows);
            while (encoded.length() > 7900 && logRows.size() > 1) {
                logRows.remove(0);
                encoded = objectMapper.writeValueAsString(logRows);
            }
            journey.setTuningLog(encoded);
        } catch (Exception ignore) { /* the ledger is best-effort */ }
        journey.setLastUpdate(OffsetDateTime.now());
        journeys.save(journey);
        return entry;
    }

    private void applyArms(Journey entity, JsonNode raw, boolean resetWeights) {
        List<Map<String, Object>> arms = parseArms(raw);
        try {
            entity.setArms(arms.isEmpty() ? null : objectMapper.writeValueAsString(arms));
        } catch (Exception e) {
            throw new BadRequestException("arms must be a JSON list of {name, subject, content}");
        }
        if (resetWeights) {
            Map<String, Integer> w = new LinkedHashMap<>();
            for (int i = 0; i < arms.size(); i++) {
                int base = 100 / arms.size();
                w.put(String.valueOf(arms.get(i).get("name")), i == 0 ? 100 - base * (arms.size() - 1) : base);
            }
            try {
                entity.setArmWeights(arms.isEmpty() ? null : objectMapper.writeValueAsString(w));
            } catch (Exception ignore) { /* equal split by construction */ }
        }
    }

    /** The author's arms document: a JSON array, the same array as a string, or nothing. */
    private List<Map<String, Object>> parseArms(JsonNode raw) {
        if (absent(raw)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = raw.isTextual()
                    ? (raw.asText().isBlank() ? List.of() : objectMapper.readValue(raw.asText(), STEP_LIST))
                    : objectMapper.convertValue(raw, STEP_LIST);
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Map<String, Object> a : list) {
                String name = str(a.get("name"));
                if (name == null || name.isBlank() || !names.add(name)) {
                    throw new BadRequestException("every arm needs a unique name");
                }
            }
            return list;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("arms must be a JSON list of {name, subject, content}");
        }
    }

    private List<Map<String, Object>> armsOf(Journey j) {
        if (j.getArms() == null || j.getArms().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(j.getArms(), STEP_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Integer> weightsOf(Journey j, List<Map<String, Object>> arms) {
        Map<String, Integer> w = new LinkedHashMap<>();
        for (int i = 0; i < arms.size(); i++) {
            int base = arms.isEmpty() ? 0 : 100 / arms.size();
            w.put(String.valueOf(arms.get(i).get("name")), i == 0 ? 100 - base * (arms.size() - 1) : base);
        }
        if (j.getArmWeights() != null && !j.getArmWeights().isBlank()) {
            try {
                Map<String, Object> saved = objectMapper.readValue(j.getArmWeights(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                for (Map.Entry<String, Object> en : saved.entrySet()) {
                    if (w.containsKey(en.getKey())) {
                        w.put(en.getKey(), ((Number) en.getValue()).intValue());
                    }
                }
            } catch (Exception ignore) { /* equal split */ }
        }
        return w;
    }

    private List<TuneEntry> tuningLogOf(Journey j) {
        if (j.getTuningLog() == null || j.getTuningLog().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(j.getTuningLog(), TUNE_LOG);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int firstMessageIndex(List<Map<String, Object>> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if ("message".equals(String.valueOf(steps.get(i).get("type")))) {
                return i;
            }
        }
        return -1;
    }

    /** The step, spoken in the enrolment's arm: the arm's subject/content replace the step's, the channel stays. */
    private Map<String, Object> withArm(Journey journey, JourneyEnrollment enrollment, Map<String, Object> step) {
        if (enrollment.getArm() == null) {
            return step;
        }
        for (Map<String, Object> a : armsOf(journey)) {
            if (enrollment.getArm().equals(str(a.get("name")))) {
                Map<String, Object> spoken = new LinkedHashMap<>(step);
                if (a.get("subject") != null) spoken.put("subject", a.get("subject"));
                if (a.get("content") != null) spoken.put("content", a.get("content"));
                if (a.get("templateRef") != null) spoken.put("templateRef", a.get("templateRef"));
                return spoken;
            }
        }
        return step;
    }

    private List<ArmRow> armRows(Journey journey, List<Map<String, Object>> arms, List<JourneyEnrollment> all) {
        Map<String, Integer> w = weightsOf(journey, arms);
        List<ArmRow> rows = new java.util.ArrayList<>();
        for (Map<String, Object> a : arms) {
            String name = str(a.get("name"));
            List<JourneyEnrollment> mine = all.stream().filter(e -> name.equals(e.getArm()) && !"holdout".equals(e.getVariant())).toList();
            long conv = mine.stream().filter(e -> "converted".equals(e.getStatus())).count();
            java.math.BigDecimal revenue = mine.stream().filter(e -> e.getConversionValue() != null)
                    .map(JourneyEnrollment::getConversionValue).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            rows.add(new ArmRow(name, w.getOrDefault(name, 0), mine.size(), conv,
                    mine.isEmpty() ? 0.0 : Math.round((double) conv / mine.size() * 1000) / 10.0, revenue));
        }
        return rows;
    }
}
