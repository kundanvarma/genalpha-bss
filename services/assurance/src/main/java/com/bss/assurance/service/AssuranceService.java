package com.bss.assurance.service;

import com.bss.assurance.api.ApiConstants;
import com.bss.assurance.dto.AlarmPatch;
import com.bss.assurance.dto.AlarmRef;
import com.bss.assurance.dto.AlarmRequest;
import com.bss.assurance.dto.AlarmView;
import com.bss.assurance.dto.Json;
import com.bss.assurance.dto.PartyRef;
import com.bss.assurance.dto.ServiceProblemRequest;
import com.bss.assurance.dto.ServiceProblemView;
import com.bss.assurance.entity.Alarm;
import com.bss.assurance.entity.ServiceProblem;
import com.bss.assurance.events.DomainEventPublisher;
import com.bss.assurance.exception.BadRequestException;
import com.bss.assurance.exception.NotFoundException;
import com.bss.assurance.repository.AlarmRepository;
import com.bss.assurance.repository.ServiceProblemRepository;
import com.bss.assurance.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The assurance loop, thin: TMF642 alarms arrive from the network (a
 * simulator in dev), CRITICAL ones automatically become a TMF656 service
 * problem — one open problem per affected object, however many alarms pile
 * on. Resolving the problem clears its alarms. Agents read both; the CSR
 * console shows open problems as an outage banner.
 */
@Service
public class AssuranceService {

    private final AlarmRepository alarms;
    private final ServiceProblemRepository problems;
    private final com.bss.assurance.service.SelfHealService selfHeal;
    private final DomainEventPublisher events;
    private final SlaService slaService;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssuranceService(AlarmRepository alarms, ServiceProblemRepository problems,
            DomainEventPublisher events, TenantScope tenantScope,
            com.bss.assurance.service.SelfHealService selfHeal,
            SlaService slaService) {
        this.alarms = alarms;
        this.problems = problems;
        this.selfHeal = selfHeal;
        this.events = events;
        this.tenantScope = tenantScope;
        this.slaService = slaService;
    }

    @Transactional
    public AlarmView raiseAlarm(AlarmRequest body) {
        AlarmRequest dto = body == null ? AlarmRequest.EMPTY : body;
        if (!dto.complete()) {
            throw new BadRequestException("alarmedObject and perceivedSeverity are required");
        }
        String tenant = tenantScope.currentTenantId();
        Alarm alarm = new Alarm();
        String id = UUID.randomUUID().toString();
        alarm.setId(id);
        alarm.setTenantId(tenant);
        alarm.setHref(ApiConstants.ALARM_BASE + "/alarm/" + id);
        alarm.setAlarmedObject(dto.alarmedObjectId());
        alarm.setAlarmType(dto.alarmTypeOr("equipmentAlarm"));
        alarm.setSeverity(dto.severity().toLowerCase(Locale.ROOT));
        alarm.setState(Alarm.RAISED);
        alarm.setProbableCause(dto.probableCauseOrNull());
        alarm.setSourceSystemId(dto.sourceSystemIdOr("network"));
        // microsecond precision: what Postgres stores — so the timestamp a
        // client captures from the POST echo survives every later read
        alarm.setRaisedAt(OffsetDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        alarms.save(alarm);

        // Critical alarms open (or join) the object's service problem.
        if (Alarm.CRITICAL.equals(alarm.getSeverity())
                && problems.findFirstByTenantIdAndAffectedObjectAndStatus(
                        tenant, alarm.getAlarmedObject(), ServiceProblem.OPEN).isEmpty()) {
            ServiceProblem problem = new ServiceProblem();
            String problemId = UUID.randomUUID().toString();
            problem.setId(problemId);
            problem.setTenantId(tenant);
            problem.setHref(ApiConstants.PROBLEM_BASE + "/serviceProblem/" + problemId);
            problem.setName("Outage: " + alarm.getAlarmedObject());
            problem.setDescription(alarm.getProbableCause() != null ? alarm.getProbableCause()
                    : "critical alarm on " + alarm.getAlarmedObject());
            problem.setStatus(ServiceProblem.OPEN);
            problem.setAffectedObject(alarm.getAlarmedObject());
            problem.setOriginAlarmId(id);
            problem.setCreatedAt(OffsetDateTime.now());
            problem.setLastUpdate(OffsetDateTime.now());
            problems.save(problem);
            events.publish("ServiceProblemCreateEvent", "serviceProblem", problemView(problem));

            // Autonomy: if the failed object is a delivery path we can
            // re-home, fix it now and close the loop ourselves.
            if (selfHeal.attemptHeal(alarm.getAlarmedObject()) > 0) {
                problem.setStatus(ServiceProblem.RESOLVED);
                problem.setResolvedAt(OffsetDateTime.now());
                problem.setDescription(problem.getDescription()
                        + " — self-healed: affected services re-homed to edge, SLA restored");
                problem.setLastUpdate(OffsetDateTime.now());
                problems.save(problem);
                alarm.setState(Alarm.CLEARED);
                alarm.setClearedAt(OffsetDateTime.now());
                alarms.save(alarm);
                events.publish("ServiceProblemStateChangeEvent", "serviceProblem", problemView(problem));
        slaService.onProblemResolved(problem); // did any signed promise break?
            }
        }
        return alarmView(alarm);
    }

    @Transactional(readOnly = true)
    public List<JsonNode> alarms(Map<String, String> filters, String fields) {
        String tenant = tenantScope.currentTenantId();
        // the TMF630 filter and the fields= projection used to walk the map; they walk the
        // record's own tree now, so the key order and the silences are the record's
        List<JsonNode> rows = alarms.findAll().stream()
                .filter(a -> tenant.equals(a.getTenantId()))
                .map(this::alarmView)
                .<JsonNode>map(objectMapper::valueToTree)
                .filter(m -> filters.entrySet().stream().allMatch(f ->
                        f.getValue() == null
                                || unquote(f.getValue()).equals(Json.valueOf(m.get(f.getKey())))))
                .toList();
        if (fields == null || fields.isBlank()) {
            return rows;
        }
        // TMF630 attribute selection: id and href always ride along
        List<String> keep = new java.util.ArrayList<>(List.of("id", "href"));
        for (String f : fields.split(",")) {
            keep.add(f.trim());
        }
        return rows.stream().<JsonNode>map(m -> {
            ObjectNode slim = objectMapper.createObjectNode();
            for (String k : keep) {
                if (m.has(k)) {
                    slim.set(k, m.get(k));
                }
            }
            return slim;
        }).toList();
    }

    /** TMF630 filter values may arrive quoted: state='raised'. */
    private static String unquote(String v) {
        return v.length() >= 2 && v.startsWith("'") && v.endsWith("'")
                ? v.substring(1, v.length() - 1) : v;
    }

    @Transactional(readOnly = true)
    public AlarmView alarmById(String id) {
        return alarms.findById(id)
                .filter(a -> tenantScope.currentTenantId().equals(a.getTenantId()))
                .map(this::alarmView)
                .orElseThrow(() -> NotFoundException.forResource("Alarm", id));
    }

    /** EMS-grade attribute updates: cause, severity, type, state. */
    @Transactional
    public AlarmView patchAlarm(String id, AlarmPatch body) {
        AlarmPatch patch = body == null ? AlarmPatch.EMPTY : body;
        Alarm alarm = alarms.findById(id)
                .filter(a -> tenantScope.currentTenantId().equals(a.getTenantId()))
                .orElseThrow(() -> NotFoundException.forResource("Alarm", id));
        if (Json.set(patch.probableCause())) {
            alarm.setProbableCause(Json.valueOf(patch.probableCause()));
        }
        if (Json.set(patch.perceivedSeverity())) {
            alarm.setSeverity(Json.valueOf(patch.perceivedSeverity()).toLowerCase(Locale.ROOT));
        }
        if (Json.set(patch.alarmType())) {
            alarm.setAlarmType(Json.valueOf(patch.alarmType()));
        }
        if (Json.set(patch.state())) {
            alarm.setState(Json.valueOf(patch.state()));
        }
        alarms.save(alarm);
        return alarmView(alarm);
    }

    @Transactional(readOnly = true)
    public List<ServiceProblemView> problems(String status) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceProblem> rows = status != null
                ? problems.findByTenantIdAndStatus(tenant, status)
                : problems.findAll().stream().filter(p -> tenant.equals(p.getTenantId())).toList();
        return rows.stream().map(this::problemView).toList();
    }

    @Transactional(readOnly = true)
    public ServiceProblemView problemById(String id) {
        return problems.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .map(this::problemView)
                .orElseThrow(() -> NotFoundException.forResource("ServiceProblem", id));
    }

    /** TMF656: a problem DECLARED from outside the alarm loop (a NOC, a
     * partner system) — recorded with who declared it and why. */
    @Transactional
    public ServiceProblemView createProblem(ServiceProblemRequest body) {
        ServiceProblemRequest dto = body == null ? ServiceProblemRequest.EMPTY : body;
        JsonNode originator = dto.originator();
        if (originator == null) {
            throw new BadRequestException(
                    "originatorParty {role} is required — a problem is DECLARED by someone");
        }
        if (!Json.set(dto.description())) {
            throw new BadRequestException("description is required");
        }
        String tenant = tenantScope.currentTenantId();
        ServiceProblem problem = new ServiceProblem();
        String id = UUID.randomUUID().toString();
        problem.setId(id);
        problem.setTenantId(tenant);
        problem.setHref(ApiConstants.PROBLEM_BASE + "/serviceProblem/" + id);
        problem.setName(dto.nameOrDescription());
        problem.setDescription(dto.descriptionValue());
        problem.setStatus(ServiceProblem.OPEN);
        problem.setAffectedObject(dto.affectedObjectOr("declared"));
        problem.setCategory(dto.categoryOr("serviceProvider.declared"));
        problem.setPriority(dto.priorityOr(2));
        problem.setReason(dto.reasonOr("unknown"));
        try {
            problem.setOriginatorJson(objectMapper.writeValueAsString(originator));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new BadRequestException("unserializable originatorParty");
        }
        problem.setAffectedServices(0); // nothing measured yet — an honest zero
        problem.setCreatedAt(OffsetDateTime.now());
        problem.setLastUpdate(OffsetDateTime.now());
        problems.save(problem);
        ServiceProblemView created = problemView(problem);
        events.publish("ServiceProblemCreateEvent", "serviceProblem", created);
        return created;
    }

    /** Resolving the problem clears every raised alarm on its object. */
    @Transactional
    public ServiceProblemView resolveProblem(String id) {
        String tenant = tenantScope.currentTenantId();
        ServiceProblem problem = problems.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("ServiceProblem", id));
        problem.setStatus(ServiceProblem.RESOLVED);
        problem.setResolvedAt(OffsetDateTime.now());
        problem.setLastUpdate(OffsetDateTime.now());
        problems.save(problem);
        for (Alarm alarm : alarms.findByTenantIdAndState(tenant, Alarm.RAISED)) {
            if (alarm.getAlarmedObject().equals(problem.getAffectedObject())) {
                alarm.setState(Alarm.CLEARED);
                alarm.setClearedAt(OffsetDateTime.now());
                alarms.save(alarm);
            }
        }
        ServiceProblemView resolved = problemView(problem);
        events.publish("ServiceProblemStateChangeEvent", "serviceProblem", resolved);
        slaService.onProblemResolved(problem); // did any signed promise break?
        return resolved;
    }

    private AlarmView alarmView(Alarm a) {
        // TMF642 mandatory attributes ride EVERY row, house-raised included
        return new AlarmView(a.getId(), a.getHref(), a.getAlarmedObject(), a.getAlarmType(),
                a.getSeverity(), a.getState(),
                a.getProbableCause() == null ? "unknown" : a.getProbableCause(),
                a.getSourceSystemId() == null ? "network" : a.getSourceSystemId(),
                a.getRaisedAt().toString());
    }

    private ServiceProblemView problemView(ServiceProblem p) {
        // TMF656 mandatory attributes ride EVERY row — alarm-born problems
        // derive them from what the loop factually knows; the alarmed OBJECT is the one
        // affected thing the loop can vouch for, a lower bound, never an invented count
        return new ServiceProblemView(
                p.getId(),
                p.getHref(),
                p.getName(),
                p.getDescription() == null ? p.getName() : p.getDescription(),
                p.getStatus(),
                p.getAffectedObject(),
                p.getCategory() == null ? "serviceProvider.declared" : p.getCategory(),
                p.getPriority() == null ? 1 : p.getPriority(),
                p.getReason() != null ? p.getReason()
                        : p.getDescription() != null ? p.getDescription() : "unknown",
                readOriginator(p),
                PartyRef.operations(p.getTenantId()),
                p.getAffectedServices() == null
                        ? (p.getOriginAlarmId() != null ? 1 : 0) : p.getAffectedServices(),
                p.getCreatedAt().toString(),
                p.getLastUpdate().toString(),
                p.getLastUpdate().toString(),
                p.getOriginAlarmId() == null ? null : List.of(new AlarmRef(p.getOriginAlarmId())));
    }

    /** The declaring system's own block, verbatim — or the monitoring system that noticed. */
    private JsonNode readOriginator(ServiceProblem p) {
        if (p.getOriginatorJson() != null) {
            try {
                return objectMapper.readTree(p.getOriginatorJson());
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // fall through to the monitoring-system default
            }
        }
        return objectMapper.valueToTree(PartyRef.monitoringSystem());
    }
}
