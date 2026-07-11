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
    private final TenantScope tenantScope;

    public AssuranceService(AlarmRepository alarms, ServiceProblemRepository problems,
            DomainEventPublisher events, TenantScope tenantScope,
            com.bss.assurance.service.SelfHealService selfHeal) {
        this.alarms = alarms;
        this.problems = problems;
        this.selfHeal = selfHeal;
        this.events = events;
        this.tenantScope = tenantScope;
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
        alarm.setAlarmedObject(String.valueOf(dto.get("alarmedObject")));
        alarm.setAlarmType(dto.get("alarmType") == null ? "equipmentAlarm"
                : String.valueOf(dto.get("alarmType")));
        alarm.setSeverity(String.valueOf(dto.get("perceivedSeverity")).toLowerCase(Locale.ROOT));
        alarm.setState(Alarm.RAISED);
        alarm.setProbableCause(dto.get("probableCause") == null ? null
                : String.valueOf(dto.get("probableCause")));
        alarm.setRaisedAt(OffsetDateTime.now());
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
            }
        }
        return alarmMap(alarm);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> alarms(String state) {
        String tenant = tenantScope.currentTenantId();
        List<Alarm> rows = state != null
                ? alarms.findByTenantIdAndState(tenant, state)
                : alarms.findAll().stream().filter(a -> tenant.equals(a.getTenantId())).toList();
        return rows.stream().map(this::alarmMap).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> problems(String status) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceProblem> rows = status != null
                ? problems.findByTenantIdAndStatus(tenant, status)
                : problems.findAll().stream().filter(p -> tenant.equals(p.getTenantId())).toList();
        return rows.stream().map(this::problemMap).toList();
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
        if (a.getProbableCause() != null) map.put("probableCause", a.getProbableCause());
        map.put("alarmRaisedTime", a.getRaisedAt().toString());
        map.put("@type", "Alarm");
        return map;
    }

    private Map<String, Object> problemMap(ServiceProblem p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("href", p.getHref());
        map.put("name", p.getName());
        if (p.getDescription() != null) map.put("description", p.getDescription());
        map.put("status", p.getStatus());
        map.put("affectedObject", p.getAffectedObject());
        if (p.getOriginAlarmId() != null) {
            map.put("underlyingAlarm", List.of(Map.of("id", p.getOriginAlarmId())));
        }
        map.put("@type", "ServiceProblem");
        return map;
    }
}
