package com.bss.assurance.service;

import com.bss.assurance.api.ApiConstants;
import com.bss.assurance.entity.Alarm;
import com.bss.assurance.entity.ServiceProblem;
import com.bss.assurance.events.DomainEventPublisher;
import com.bss.assurance.exception.BadRequestException;
import com.bss.assurance.exception.NotFoundException;
import com.bss.assurance.repository.AlarmRepository;
import com.bss.assurance.repository.ServiceProblemRepository;
import com.bss.assurance.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
    public Map<String, Object> raiseAlarm(Map<String, Object> dto) {
        if (dto.get("alarmedObject") == null || dto.get("perceivedSeverity") == null) {
            throw new BadRequestException("alarmedObject and perceivedSeverity are required");
        }
        String tenant = tenantScope.currentTenantId();
        Alarm alarm = new Alarm();
        String id = UUID.randomUUID().toString();
        alarm.setId(id);
        alarm.setTenantId(tenant);
        alarm.setHref(ApiConstants.ALARM_BASE + "/alarm/" + id);
        Object alarmed = dto.get("alarmedObject");
        alarm.setAlarmedObject(alarmed instanceof Map<?, ?> ref && ref.get("id") != null
                ? String.valueOf(ref.get("id")) : String.valueOf(alarmed));
        alarm.setAlarmType(dto.get("alarmType") == null ? "equipmentAlarm"
                : String.valueOf(dto.get("alarmType")));
        alarm.setSeverity(String.valueOf(dto.get("perceivedSeverity")).toLowerCase(Locale.ROOT));
        alarm.setState(Alarm.RAISED);
        alarm.setProbableCause(dto.get("probableCause") == null ? null
                : String.valueOf(dto.get("probableCause")));
        alarm.setSourceSystemId(dto.get("sourceSystemId") == null ? "network"
                : String.valueOf(dto.get("sourceSystemId")));
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
            events.publish("ServiceProblemCreateEvent", "serviceProblem", problemMap(problem));

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
                events.publish("ServiceProblemStateChangeEvent", "serviceProblem", problemMap(problem));
        slaService.onProblemResolved(problem); // did any signed promise break?
            }
        }
        return alarmMap(alarm);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> alarms(Map<String, String> filters, String fields) {
        String tenant = tenantScope.currentTenantId();
        List<Map<String, Object>> rows = alarms.findAll().stream()
                .filter(a -> tenant.equals(a.getTenantId()))
                .map(this::alarmMap)
                .filter(m -> filters.entrySet().stream().allMatch(f ->
                        f.getValue() == null
                                || unquote(f.getValue()).equals(String.valueOf(m.get(f.getKey())))))
                .toList();
        if (fields == null || fields.isBlank()) {
            return rows;
        }
        // TMF630 attribute selection: id and href always ride along
        List<String> keep = new java.util.ArrayList<>(List.of("id", "href"));
        for (String f : fields.split(",")) {
            keep.add(f.trim());
        }
        return rows.stream().map(m -> {
            Map<String, Object> slim = new LinkedHashMap<>();
            for (String k : keep) {
                if (m.containsKey(k)) {
                    slim.put(k, m.get(k));
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
    public Map<String, Object> alarmById(String id) {
        return alarms.findById(id)
                .filter(a -> tenantScope.currentTenantId().equals(a.getTenantId()))
                .map(this::alarmMap)
                .orElseThrow(() -> NotFoundException.forResource("Alarm", id));
    }

    /** EMS-grade attribute updates: cause, severity, type, state. */
    @Transactional
    public Map<String, Object> patchAlarm(String id, Map<String, Object> patch) {
        Alarm alarm = alarms.findById(id)
                .filter(a -> tenantScope.currentTenantId().equals(a.getTenantId()))
                .orElseThrow(() -> NotFoundException.forResource("Alarm", id));
        if (patch.get("probableCause") != null) {
            alarm.setProbableCause(String.valueOf(patch.get("probableCause")));
        }
        if (patch.get("perceivedSeverity") != null) {
            alarm.setSeverity(String.valueOf(patch.get("perceivedSeverity"))
                    .toLowerCase(Locale.ROOT));
        }
        if (patch.get("alarmType") != null) {
            alarm.setAlarmType(String.valueOf(patch.get("alarmType")));
        }
        if (patch.get("state") != null) {
            alarm.setState(String.valueOf(patch.get("state")));
        }
        alarms.save(alarm);
        return alarmMap(alarm);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> problems(String status) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceProblem> rows = status != null
                ? problems.findByTenantIdAndStatus(tenant, status)
                : problems.findAll().stream().filter(p -> tenant.equals(p.getTenantId())).toList();
        return rows.stream().map(this::problemMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> problemById(String id) {
        return problems.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .map(this::problemMap)
                .orElseThrow(() -> NotFoundException.forResource("ServiceProblem", id));
    }

    /** TMF656: a problem DECLARED from outside the alarm loop (a NOC, a
     * partner system) — recorded with who declared it and why. */
    @Transactional
    public Map<String, Object> createProblem(Map<String, Object> dto) {
        if (!(dto.get("originatorParty") instanceof Map<?, ?> originator)
                || originator.get("role") == null) {
            throw new BadRequestException(
                    "originatorParty {role} is required — a problem is DECLARED by someone");
        }
        if (dto.get("description") == null) {
            throw new BadRequestException("description is required");
        }
        String tenant = tenantScope.currentTenantId();
        ServiceProblem problem = new ServiceProblem();
        String id = UUID.randomUUID().toString();
        problem.setId(id);
        problem.setTenantId(tenant);
        problem.setHref(ApiConstants.PROBLEM_BASE + "/serviceProblem/" + id);
        problem.setName(dto.get("name") == null ? String.valueOf(dto.get("description"))
                : String.valueOf(dto.get("name")));
        problem.setDescription(String.valueOf(dto.get("description")));
        problem.setStatus(ServiceProblem.OPEN);
        problem.setAffectedObject(dto.get("affectedObject") == null ? "declared"
                : String.valueOf(dto.get("affectedObject")));
        problem.setCategory(dto.get("category") == null ? "serviceProvider.declared"
                : String.valueOf(dto.get("category")));
        problem.setPriority(dto.get("priority") instanceof Number n ? n.intValue()
                : dto.get("priority") != null ? Integer.parseInt(String.valueOf(dto.get("priority")))
                        : 2);
        problem.setReason(dto.get("reason") == null ? "unknown"
                : String.valueOf(dto.get("reason")));
        try {
            problem.setOriginatorJson(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(originator));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new BadRequestException("unserializable originatorParty");
        }
        problem.setAffectedServices(0); // nothing measured yet — an honest zero
        problem.setCreatedAt(OffsetDateTime.now());
        problem.setLastUpdate(OffsetDateTime.now());
        problems.save(problem);
        Map<String, Object> created = problemMap(problem);
        events.publish("ServiceProblemCreateEvent", "serviceProblem", created);
        return created;
    }

    /** Resolving the problem clears every raised alarm on its object. */
    @Transactional
    public Map<String, Object> resolveProblem(String id) {
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
        Map<String, Object> resolved = problemMap(problem);
        events.publish("ServiceProblemStateChangeEvent", "serviceProblem", resolved);
        slaService.onProblemResolved(problem); // did any signed promise break?
        return resolved;
    }

    private Map<String, Object> alarmMap(Alarm a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("href", a.getHref());
        map.put("alarmedObject", a.getAlarmedObject());
        map.put("alarmType", a.getAlarmType());
        map.put("perceivedSeverity", a.getSeverity());
        map.put("state", a.getState());
        // TMF642 mandatory attributes ride EVERY row, house-raised included
        map.put("probableCause", a.getProbableCause() == null ? "unknown" : a.getProbableCause());
        map.put("sourceSystemId", a.getSourceSystemId() == null ? "network" : a.getSourceSystemId());
        map.put("alarmRaisedTime", a.getRaisedAt().toString());
        map.put("@type", "Alarm");
        return map;
    }

    private Map<String, Object> problemMap(ServiceProblem p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("href", p.getHref());
        map.put("name", p.getName());
        map.put("description", p.getDescription() == null ? p.getName() : p.getDescription());
        map.put("status", p.getStatus());
        map.put("affectedObject", p.getAffectedObject());
        // TMF656 mandatory attributes ride EVERY row — alarm-born problems
        // derive them from what the loop factually knows
        map.put("category", p.getCategory() == null
                ? "serviceProvider.declared" : p.getCategory());
        map.put("priority", p.getPriority() == null ? 1 : p.getPriority());
        map.put("reason", p.getReason() != null ? p.getReason()
                : p.getDescription() != null ? p.getDescription() : "unknown");
        map.put("originatorParty", readOriginator(p));
        map.put("responsibleParty", Map.of("id", "op-" + p.getTenantId(),
                "role", "operations", "name", "network operations"));
        // the alarmed OBJECT is the one affected thing the loop can vouch
        // for — a conservative lower bound, never an invented count
        map.put("affectedNumberOfServices", p.getAffectedServices() == null
                ? (p.getOriginAlarmId() != null ? 1 : 0) : p.getAffectedServices());
        map.put("timeRaised", p.getCreatedAt().toString());
        map.put("timeChanged", p.getLastUpdate().toString());
        map.put("statusChangeDate", p.getLastUpdate().toString());
        if (p.getOriginAlarmId() != null) {
            map.put("underlyingAlarm", List.of(Map.of("id", p.getOriginAlarmId())));
        }
        map.put("@type", "ServiceProblem");
        return map;
    }

    private Map<String, Object> readOriginator(ServiceProblem p) {
        if (p.getOriginatorJson() != null) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(p.getOriginatorJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // fall through to the monitoring-system default
            }
        }
        return Map.of("id", "assurance", "role", "monitoringSystem",
                "name", "assurance loop");
    }
}
