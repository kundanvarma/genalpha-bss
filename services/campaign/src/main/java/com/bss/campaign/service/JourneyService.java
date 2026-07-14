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

    public JourneyService(JourneyRepository journeys, JourneyEnrollmentRepository enrollments,
            CommunicationClient communication, InsightClient insight,
            TenantScope tenantScope, TenantRegistry tenants, ObjectMapper objectMapper) {
        this.journeys = journeys;
        this.enrollments = enrollments;
        this.communication = communication;
        this.insight = insight;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.objectMapper = objectMapper;
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
                if (step.get("subject") == null || step.get("content") == null) {
                    throw new BadRequestException("message steps need subject and content");
                }
            } else if ("wait".equals(type)) {
                if (waitSeconds(step) <= 0) {
                    throw new BadRequestException("wait steps need seconds/minutes/hours/days > 0");
                }
            } else {
                throw new BadRequestException("step type must be 'message' or 'wait'");
            }
        }
        Journey entity = new Journey();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/journey/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setStatus(dto.get("status") == null ? Journey.ACTIVE : String.valueOf(dto.get("status")));
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
        return journeys.findByTenantId(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Journey entity = journeys.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Journey", id));
        if (patch.get("status") != null) {
            String status = String.valueOf(patch.get("status"));
            if (!Journey.ACTIVE.equals(status) && !Journey.PAUSED.equals(status)) {
                throw new BadRequestException("status must be active or paused");
            }
            entity.setStatus(status);
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(journeys.save(entity));
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
    public void onEvent(String eventType, String state, String partyId) {
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
                enrollment.setStatus("converted");
                enrollment.setConvertedAt(OffsetDateTime.now());
                enrollments.save(enrollment);
                log.info("journey '{}' conversion: party {} exited from step {} ({})",
                        journey.getName(), partyId, enrollment.getStepIndex(), enrollment.getVariant());
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
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                tickTenant(tenant.getId());
            } catch (Exception e) {
                log.warn("journey tick skipped tenant '{}': {}", tenant.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void tickTenant(String tenantId) {
        for (JourneyEnrollment enrollment : enrollments
                .findTop200ByTenantIdAndStatusAndNextActionAtBefore(
                        tenantId, "active", OffsetDateTime.now())) {
            Journey journey = journeys.findByIdAndTenantId(enrollment.getJourneyId(), tenantId).orElse(null);
            if (journey == null) {
                continue;
            }
            if (!Journey.ACTIVE.equals(journey.getStatus())) {
                continue; // paused journeys stop the clock, they don't lose people
            }
            advance(journey, enrollment);
        }
    }

    /** Run steps from where they stand until a wait parks them or the end. */
    private void advance(Journey journey, JourneyEnrollment enrollment) {
        List<Map<String, Object>> steps = parseSteps(journey.getSteps());
        int index = enrollment.getStepIndex();
        while (index < steps.size()) {
            Map<String, Object> step = steps.get(index);
            if ("message".equals(String.valueOf(step.get("type")))) {
                if (!"holdout".equals(enrollment.getVariant())) {
                    String content = String.valueOf(step.get("content"));
                    if (step.get("promotionCode") != null) {
                        content = content.replace("{code}", String.valueOf(step.get("promotionCode")));
                    }
                    communication.send(enrollment.getPartyId(),
                            String.valueOf(step.get("subject")), content);
                }
                index++;
            } else { // wait
                index++;
                enrollment.setStepIndex(index);
                enrollment.setNextActionAt(OffsetDateTime.now().plusSeconds(waitSeconds(step)));
                enrollments.save(enrollment);
                return;
            }
        }
        enrollment.setStepIndex(index);
        enrollment.setStatus("completed");
        enrollment.setNextActionAt(null);
        enrollments.save(enrollment);
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
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("journeyId", journeyId);
        stats.put("entered", (long) all.size());
        stats.put("treated", treated);
        stats.put("heldOut", heldOut);
        stats.put("activeAtStep", atStep);
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
        return stats;
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
