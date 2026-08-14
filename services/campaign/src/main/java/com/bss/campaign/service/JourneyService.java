package com.bss.campaign.service;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.client.CommunicationClient;
import com.bss.campaign.client.InsightClient;
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
    private final com.bss.campaign.repository.ArbitrationDecisionRepository arbitration;

    public JourneyService(JourneyRepository journeys, JourneyEnrollmentRepository enrollments,
            CommunicationClient communication, InsightClient insight,
            TenantScope tenantScope, TenantRegistry tenants, ObjectMapper objectMapper,
            FrequencyGuard frequency, com.bss.campaign.client.CatalogClient catalog,
            com.bss.campaign.tick.TickGuard tickGuard,
            com.bss.campaign.repository.ArbitrationDecisionRepository arbitration) {
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
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("steps") == null) {
            throw new BadRequestException("name and steps are required");
        }
        List<Map<String, Object>> steps = parseSteps(dto.get("steps"));
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
        Journey entity = new Journey();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/journey/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setStatus(dto.get("status") == null ? Journey.ACTIVE : requireLifecycle(dto.get("status")));
        entity.setTriggerEventType(str(dto.get("triggerEventType")));
        entity.setTriggerState(str(dto.get("triggerState")));
        entity.setSegmentName(str(dto.get("segmentName")));
        entity.setConversionEvent(str(dto.get("conversionEvent")));
        if (dto.get("holdoutPercent") != null) {
            int holdout = Integer.parseInt(String.valueOf(dto.get("holdoutPercent")));
            if (holdout < 0 || holdout > 90) {
                throw new BadRequestException("holdoutPercent must be 0-90");
            }
            entity.setHoldoutPercent(holdout);
        }
        if (dto.get("priority") != null) {
            entity.setPriority(Integer.parseInt(String.valueOf(dto.get("priority"))));
        }
        try {
            entity.setSteps(objectMapper.writeValueAsString(steps));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("steps are not serializable JSON");
        }
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(journeys.save(entity));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        // archived journeys are a soft delete: hidden from the default list
        return journeys.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(j -> !Journey.ARCHIVED.equals(j.getStatus()))
                .map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Journey entity = journeys.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", id));
        if (patch.get("status") != null) {
            entity.setStatus(requireLifecycle(patch.get("status")));
        }
        if (patch.get("priority") != null) {
            entity.setPriority(Integer.parseInt(String.valueOf(patch.get("priority"))));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(journeys.save(entity));
    }

    /** Delete a journey and its enrollment ledger. */
    @Transactional
    public void delete(String id) {
        Journey entity = journeys.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", id));
        enrollments.deleteByTenantIdAndJourneyId(tenantScope.currentTenantId(), id);
        journeys.delete(entity);
    }

    private String requireLifecycle(Object status) {
        String value = String.valueOf(status);
        if (!Journey.LIFECYCLE.contains(value)) {
            throw new BadRequestException("status must be one of " + Journey.LIFECYCLE);
        }
        return value;
    }

    // ---------------- enrollment ----------------

    /** Segment enrollment: everyone insight puts in the segment, once. */
    @Transactional
    public Map<String, Object> enrollSegment(String journeyId) {
        Journey journey = journeys.findByIdAndTenantId(journeyId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", journeyId));
        if (journey.getSegmentName() == null || journey.getSegmentName().isBlank()) {
            throw new BadRequestException("this journey has no segment — it enrolls on events");
        }
        if (!Journey.ACTIVE.equals(journey.getStatus())) {
            throw new BadRequestException("only an active journey can enroll");
        }
        int enrolled = 0;
        for (Map<String, Object> member : insight.segmentMembers(journey.getSegmentName())) {
            if (enroll(journey, String.valueOf(member.get("partyId")))) {
                enrolled++;
            }
        }
        return Map.of("journeyId", journeyId, "segment", journey.getSegmentName(),
                "enrolled", enrolled);
    }

    /** Event entry: business events enroll customers into matching journeys. */
    @Transactional
    public void onEvent(String eventType, String state, String partyId,
            List<String> offeringIds) {
        String tenant = tenantScope.currentTenantId();
        for (Journey journey : journeys.findByTenantIdAndStatusAndTriggerEventType(
                tenant, Journey.ACTIVE, eventType)) {
            if (journey.getTriggerState() != null && !journey.getTriggerState().equals(state)) {
                continue;
            }
            enroll(journey, partyId);
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

    private boolean enroll(Journey journey, String partyId) {
        String tenant = tenantScope.currentTenantId();
        if (enrollments.existsByTenantIdAndJourneyIdAndPartyId(tenant, journey.getId(), partyId)) {
            return false;
        }
        boolean holdout = journey.getHoldoutPercent() > 0
                && Math.floorMod((journey.getId() + partyId).hashCode(), 100) < journey.getHoldoutPercent();
        JourneyEnrollment enrollment = new JourneyEnrollment();
        enrollment.setId(UUID.randomUUID().toString());
        enrollment.setTenantId(tenant);
        enrollment.setJourneyId(journey.getId());
        enrollment.setPartyId(partyId);
        enrollment.setVariant(holdout ? "holdout" : "treated");
        enrollment.setEnrolledAt(OffsetDateTime.now());
        enrollment.setNextActionAt(OffsetDateTime.now());
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
        List<JourneyEnrollment> due = enrollments
                .findTop200ByTenantIdAndStatusAndNextActionAtBefore(
                        tenantId, "active", OffsetDateTime.now());
        // Resolve each enrollment's journey once, dropping the dead/paused ones.
        Map<String, Journey> byId = new LinkedHashMap<>();
        List<JourneyEnrollment> live = new java.util.ArrayList<>();
        for (JourneyEnrollment e : due) {
            Journey j = byId.computeIfAbsent(e.getJourneyId(),
                    id -> journeys.findByIdAndTenantId(id, tenantId).orElse(null));
            if (j != null && Journey.ACTIVE.equals(j.getStatus())) {
                live.add(e);
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
        com.bss.campaign.entity.ArbitrationDecision d = new com.bss.campaign.entity.ArbitrationDecision();
        d.setId(UUID.randomUUID().toString());
        d.setTenantId(tenantId);
        d.setPartyId(enrollment.getPartyId());
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
    public List<Map<String, Object>> arbitrationDecisions(String partyId) {
        List<com.bss.campaign.entity.ArbitrationDecision> rows = partyId == null
                ? arbitration.findTop200ByTenantIdOrderByDecidedAtDesc(tenantScope.currentTenantId())
                : arbitration.findByTenantIdAndPartyIdOrderByDecidedAtDesc(tenantScope.currentTenantId(), partyId);
        return rows.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partyId", d.getPartyId());
            m.put("winnerJourneyId", d.getWinnerJourneyId());
            m.put("heldJourneyId", d.getHeldJourneyId());
            m.put("reason", d.getReason());
            m.put("decidedAt", d.getDecidedAt());
            return m;
        }).toList();
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
                if (!"holdout".equals(enrollment.getVariant()) && !sendGuarded(journey, enrollment, step)) {
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
                    .anyMatch(m -> enrollment.getPartyId().equals(String.valueOf(m.get("partyId"))));
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
        java.util.Optional<OffsetDateTime> quiet = frequency.quietUntil();
        if (quiet.isPresent()) {
            enrollment.setNextActionAt(quiet.get());
            enrollments.save(enrollment);
            log.info("journey '{}' parked for party {} — quiet hours until {}",
                    journey.getName(), enrollment.getPartyId(), quiet.get());
            return false;
        }
        if (!frequency.canSend(enrollment.getPartyId())) {
            enrollment.setNextActionAt(OffsetDateTime.now().plusSeconds(3600));
            enrollments.save(enrollment);
            log.info("journey '{}' postponed for party {} — marketing budget spent",
                    journey.getName(), enrollment.getPartyId());
            return false;
        }
        if (message.get("templateRef") != null) {
            // Reusable template: communication renders the localized, tokenized
            // copy; we pass the channel and any promo code as context.
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            if (message.get("promotionCode") != null) context.put("promotion.code", message.get("promotionCode"));
            communication.sendTemplated(enrollment.getPartyId(),
                    String.valueOf(message.get("templateRef")),
                    message.get("locale") == null ? null : String.valueOf(message.get("locale")),
                    message.get("channel") == null ? null : String.valueOf(message.get("channel")),
                    context);
        } else {
            String content = String.valueOf(message.get("content"));
            if (message.get("promotionCode") != null) {
                content = content.replace("{code}", String.valueOf(message.get("promotionCode")));
            }
            communication.send(enrollment.getPartyId(), String.valueOf(message.get("subject")), content);
        }
        frequency.record(enrollment.getPartyId(), "journey");
        return true;
    }

    // ---------------- the funnel ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> statsOf(String journeyId) {
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
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("journeyId", journeyId);
        stats.put("entered", (long) all.size());
        stats.put("treated", treated);
        stats.put("heldOut", heldOut);
        stats.put("activeAtStep", atStep);
        if (!byStage.isEmpty()) stats.put("stageFunnel", byStage);
        // BB2 — Journey Insights: a per-node funnel. For each node, how many
        // enrollments REACHED it (are at or beyond it) and how many are ACTIVE
        // there right now. The drop between consecutive nodes is where people
        // fall out — the number a journey owner reads to find the leak.
        List<Map<String, Object>> funnel = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            long reached = all.stream().filter(e -> effectiveStep(e, steps.size()) >= idx).count();
            long activeHere = all.stream()
                    .filter(e -> "active".equals(e.getStatus()) && e.getStepIndex() == idx).count();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("index", idx);
            node.put("type", steps.get(idx).get("type"));
            if (steps.get(idx).get("stage") != null) node.put("stage", steps.get(idx).get("stage"));
            node.put("reached", reached);
            node.put("active", activeHere);
            funnel.add(node);
        }
        stats.put("funnel", funnel);
        stats.put("completedUnconverted",
                all.stream().filter(e -> "completed".equals(e.getStatus())).count());
        stats.put("conversions", Map.of("treated", treatedConv, "holdout", holdoutConv));
        Double treatedRate = treated == 0 ? null : (double) treatedConv / treated;
        Double holdoutRate = heldOut == 0 ? null : (double) holdoutConv / heldOut;
        if (treatedRate != null) {
            stats.put("treatedRate", Math.round(treatedRate * 1000) / 10.0);
        }
        if (holdoutRate != null) {
            stats.put("holdoutRate", Math.round(holdoutRate * 1000) / 10.0);
        }
        if (treatedRate != null && holdoutRate != null) {
            stats.put("liftPoints", Math.round((treatedRate - holdoutRate) * 1000) / 10.0);
        }
        if (heldOut > 0 && heldOut < 5) {
            stats.put("note", "holdout under 5 people — the lift is an anecdote, not a measurement");
        }
        // attributed revenue, per exposed customer (see the campaign readout)
        java.math.BigDecimal treatedRevenue = enrollmentRevenue(all, false);
        java.math.BigDecimal holdoutRevenue = enrollmentRevenue(all, true);
        if (treatedRevenue.signum() != 0 || holdoutRevenue.signum() != 0) {
            Map<String, Object> revenue = new LinkedHashMap<>();
            revenue.put("treated", treatedRevenue);
            revenue.put("holdout", holdoutRevenue);
            if (treated > 0 && heldOut > 0) {
                revenue.put("liftPerCustomer", perCustomer(treatedRevenue, treated)
                        .subtract(perCustomer(holdoutRevenue, heldOut)));
            }
            revenue.put("basis", "monthly recurring value of converting orders");
            stats.put("revenue", revenue);
        }
        return stats;
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(Object steps) {
        try {
            if (steps instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return objectMapper.readValue(String.valueOf(steps), STEP_LIST);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
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

    private Map<String, Object> toMap(Journey j) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", j.getId());
        map.put("href", j.getHref());
        map.put("name", j.getName());
        map.put("status", j.getStatus());
        if (j.getTriggerEventType() != null) map.put("triggerEventType", j.getTriggerEventType());
        if (j.getTriggerState() != null) map.put("triggerState", j.getTriggerState());
        if (j.getSegmentName() != null) map.put("segmentName", j.getSegmentName());
        if (j.getConversionEvent() != null) map.put("conversionEvent", j.getConversionEvent());
        map.put("holdoutPercent", j.getHoldoutPercent());
        map.put("priority", j.getPriority());
        try {
            map.put("steps", objectMapper.readValue(j.getSteps(), STEP_LIST));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            map.put("steps", j.getSteps());
        }
        map.put("lastUpdate", j.getLastUpdate());
        map.put("@type", "Journey");
        return map;
    }
}
