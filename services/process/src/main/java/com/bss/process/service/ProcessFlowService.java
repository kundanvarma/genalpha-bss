package com.bss.process.service;

import com.bss.process.entity.ProcessEvent;
import com.bss.process.entity.ProcessFlow;
import com.bss.process.entity.ProcessFlowSpec;
import com.bss.process.entity.TaskFlow;
import com.bss.process.events.DomainEventPublisher;
import com.bss.process.exception.BadRequestException;
import com.bss.process.exception.NotFoundException;
import com.bss.process.repository.ProcessEventRepository;
import com.bss.process.repository.ProcessFlowRepository;
import com.bss.process.repository.ProcessFlowSpecRepository;
import com.bss.process.repository.TaskFlowRepository;
import com.bss.process.security.PartyScope;
import com.bss.process.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF701, the house way: the SPECIFICATION half is design intent as
 * data (what each task owes, and by when); the RUN-TIME half is
 * projected from the events the fleet already publishes — choreography
 * keeps running the flows, this layer explains them. STUCK IS A STATE:
 * a task past its owed allowance goes failed, out loud, on the bus.
 */
@Service
public class ProcessFlowService {

    private static final Logger log = LoggerFactory.getLogger(ProcessFlowService.class);

    private final ProcessFlowSpecRepository specs;
    private final ProcessFlowRepository flows;
    private final TaskFlowRepository tasks;
    private final ProcessEventRepository journal;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProcessFlowService(ProcessFlowSpecRepository specs, ProcessFlowRepository flows,
            TaskFlowRepository tasks, ProcessEventRepository journal, DomainEventPublisher events,
            TenantScope tenantScope, PartyScope partyScope) {
        this.partyScope = partyScope;
        this.specs = specs;
        this.flows = flows;
        this.tasks = tasks;
        this.journal = journal;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    /* ---------- the seeded specs (editable data, never code) ---------- */

    @Transactional
    public void seedDefaults(String tenant) {
        seedSpec(tenant, "order-digital", "Order to activation (digital)",
                "A digital order provisions and activates without human hands.",
                List.of(
                        task("placed", "Order placed", 0),
                        task("provisioned", "Service provisioned & activated", 120),
                        task("completed", "Order completed", 180)));
        seedSpec(tenant, "order-physical", "Order with physical fulfilment",
                "Hardware or installation: the flow WAITS for the warehouse/installer callback.",
                List.of(
                        task("placed", "Order placed", 0),
                        task("fulfilled", "Physical fulfilment (staff callback)", 7 * 24 * 3600),
                        task("provisioned", "Service provisioned & activated", 120),
                        task("completed", "Order completed", 180)));
        seedSpec(tenant, "order-held", "Order held for approval",
                "A dependent's order awaits the payer's decision.",
                List.of(
                        task("placed", "Order placed", 0),
                        task("approved", "Payer approval", 3 * 24 * 3600),
                        task("provisioned", "Service provisioned & activated", 120),
                        task("completed", "Order completed", 180)));
    }

    private Map<String, Object> task(String code, String name, long allowanceSeconds) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", code);
        t.put("name", name);
        t.put("allowanceSeconds", allowanceSeconds);
        return t;
    }

    private void seedSpec(String tenant, String code, String name, String description,
            List<Map<String, Object>> taskSpecs) {
        if (specs.findByTenantIdAndCode(tenant, code).isPresent()) {
            return;
        }
        ProcessFlowSpec spec = new ProcessFlowSpec();
        spec.setTenantId(tenant);
        spec.setCode(code);
        spec.setName(name);
        spec.setDescription(description);
        spec.setTasksJson(writeJson(taskSpecs));
        spec.setLastUpdate(OffsetDateTime.now());
        specs.save(spec);
    }

    /* ---------- projection (called by the listener, acting as tenant) ---------- */

    /** An order was placed: a flow begins. Spec picked from the order's own shape. */
    @Transactional
    public void onOrderCreated(String orderId, String partyId, boolean physical, Map<String, Object> resource) {
        String tenant = tenantScope.currentTenantId();
        seedDefaults(tenant);
        if (flows.findByTenantIdAndCorrelationId(tenant, orderId).isPresent()) {
            return; // at-least-once delivery
        }
        String specCode = physical ? "order-physical" : "order-digital";
        ProcessFlowSpec spec = specs.findByTenantIdAndCode(tenant, specCode).orElseThrow();
        ProcessFlow flow = new ProcessFlow();
        flow.setId(UUID.randomUUID().toString());
        flow.setTenantId(tenant);
        flow.setSpecCode(specCode);
        flow.setCorrelationId(orderId);
        flow.setPartyId(partyId);
        flow.setState(ProcessFlow.IN_PROGRESS);
        flow.setStartedAt(OffsetDateTime.now());
        flow.setLastUpdate(OffsetDateTime.now());
        flows.save(flow);
        int seq = 0;
        for (Map<String, Object> t : readTasks(spec.getTasksJson())) {
            TaskFlow tf = new TaskFlow();
            tf.setId(UUID.randomUUID().toString());
            tf.setTenantId(tenant);
            tf.setProcessFlowId(flow.getId());
            tf.setCode(String.valueOf(t.get("code")));
            tf.setName(String.valueOf(t.get("name")));
            tf.setSeq(seq++);
            tf.setAllowanceSeconds(t.get("allowanceSeconds") == null ? null
                    : Long.valueOf(String.valueOf(t.get("allowanceSeconds"))));
            boolean first = "placed".equals(t.get("code"));
            tf.setState(first ? TaskFlow.COMPLETED : tf.getSeq() == 1 ? TaskFlow.IN_PROGRESS : TaskFlow.PENDING);
            tf.setStartedAt(OffsetDateTime.now());
            if (first) {
                tf.setCompletedAt(OffsetDateTime.now());
            }
            tasks.save(tf);
        }
        record(flow, "ProductOrderCreateEvent", "bss.ordering.events", resource);
    }

    /** A correlated milestone arrived: advance the matching task. */
    @Transactional
    public void onMilestone(String orderId, String taskCode, String eventType, String topic,
            Map<String, Object> resource) {
        String tenant = tenantScope.currentTenantId();
        ProcessFlow flow = flows.findByTenantIdAndCorrelationId(tenant, orderId).orElse(null);
        if (flow == null) {
            return; // an order from before this layer existed
        }
        record(flow, eventType, topic, resource);
        List<TaskFlow> flowTasks = tasks.findAllByTenantIdAndProcessFlowIdOrderBySeqAsc(tenant, flow.getId());
        if ("held".equals(taskCode)) {
            // the approval task holds; if the spec has none, the current task holds
            TaskFlow held = flowTasks.stream().filter(t -> "approved".equals(t.getCode())).findFirst()
                    .orElseGet(() -> current(flowTasks));
            if (held != null && !TaskFlow.COMPLETED.equals(held.getState())) {
                held.setState(TaskFlow.HELD);
                held.setMessage("order held awaiting approval");
                tasks.save(held);
            }
            return;
        }
        if ("cancelled".equals(taskCode)) {
            flow.setState(ProcessFlow.CANCELLED);
            flow.setCompletedAt(OffsetDateTime.now());
            flow.setLastUpdate(OffsetDateTime.now());
            flows.save(flow);
            publishFlowState(flow, "order cancelled");
            return;
        }
        // complete every task up to and including the milestone's task
        boolean reached = false;
        for (TaskFlow t : flowTasks) {
            if (!reached && !TaskFlow.COMPLETED.equals(t.getState())) {
                t.setState(TaskFlow.COMPLETED);
                t.setCompletedAt(OffsetDateTime.now());
                t.setMessage(eventType);
                tasks.save(t);
            }
            if (t.getCode().equals(taskCode)) {
                reached = true;
                // open the next task
                flowTasks.stream().filter(n -> n.getSeq() == t.getSeq() + 1).findFirst()
                        .ifPresent(n -> {
                            if (TaskFlow.PENDING.equals(n.getState())) {
                                n.setState(TaskFlow.IN_PROGRESS);
                                n.setStartedAt(OffsetDateTime.now());
                                tasks.save(n);
                            }
                        });
                break;
            }
        }
        boolean allDone = tasks.findAllByTenantIdAndProcessFlowIdOrderBySeqAsc(tenant, flow.getId())
                .stream().allMatch(t -> TaskFlow.COMPLETED.equals(t.getState()));
        if (allDone && !ProcessFlow.COMPLETED.equals(flow.getState())) {
            flow.setState(ProcessFlow.COMPLETED);
            flow.setCompletedAt(OffsetDateTime.now());
            flow.setLastUpdate(OffsetDateTime.now());
            flows.save(flow);
            publishFlowState(flow, "all tasks completed");
        }
    }

    /* ---------- the stuck sweep (TickGuard-called) ---------- */

    /** A task past its owed allowance goes FAILED — stuck is a state, said out loud. */
    @Transactional
    public int sweepStuck() {
        int failed = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (ProcessFlow flow : flows.findByStateOrderByStartedAtAsc(ProcessFlow.IN_PROGRESS)) {
            for (TaskFlow t : tasks.findAllByTenantIdAndProcessFlowIdOrderBySeqAsc(
                    flow.getTenantId(), flow.getId())) {
                boolean open = TaskFlow.IN_PROGRESS.equals(t.getState())
                        || TaskFlow.HELD.equals(t.getState());
                if (!open || t.getAllowanceSeconds() == null || t.getAllowanceSeconds() <= 0
                        || t.getStartedAt() == null) {
                    continue;
                }
                if (Duration.between(t.getStartedAt(), now).getSeconds() > t.getAllowanceSeconds()) {
                    t.setState(TaskFlow.FAILED);
                    t.setMessage("owed within " + t.getAllowanceSeconds() + "s of "
                            + t.getStartedAt() + " — nothing arrived");
                    t.setCompletedAt(now);
                    tasks.save(t);
                    flow.setState(ProcessFlow.FAILED);
                    flow.setMessage("task '" + t.getCode() + "' " + t.getMessage());
                    flow.setLastUpdate(now);
                    flows.save(flow);
                    publishTaskState(flow, t);
                    publishFlowState(flow, flow.getMessage());
                    failed++;
                    log.info("process: flow {} FAILED at task {} (order {})",
                            flow.getId(), t.getCode(), flow.getCorrelationId());
                    break;
                }
            }
        }
        return failed;
    }

    /* ---------- reads + the operator lever ---------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSpecs() {
        String tenant = tenantScope.currentTenantId();
        seedIfEmpty(tenant);
        return specs.findAllByTenantIdOrderByCodeAsc(tenant).stream().map(this::specView).toList();
    }

    @Transactional
    public Map<String, Object> upsertSpec(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        if (dto.get("code") == null) {
            throw new BadRequestException("code is required");
        }
        ProcessFlowSpec spec = specs.findByTenantIdAndCode(tenant, String.valueOf(dto.get("code")))
                .orElseGet(() -> {
                    ProcessFlowSpec s = new ProcessFlowSpec();
                    s.setTenantId(tenant);
                    s.setCode(String.valueOf(dto.get("code")));
                    return s;
                });
        if (dto.get("name") != null) {
            spec.setName(String.valueOf(dto.get("name")));
        }
        if (dto.get("description") != null) {
            spec.setDescription(String.valueOf(dto.get("description")));
        }
        if (dto.get("taskFlowSpecification") instanceof List<?> t) {
            spec.setTasksJson(writeJson(t));
        }
        spec.setLastUpdate(OffsetDateTime.now());
        specs.save(spec);
        return specView(spec);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listFlows(String state, String correlationId) {
        String tenant = tenantScope.currentTenantId();
        List<ProcessFlow> found = correlationId != null
                ? flows.findByTenantIdAndCorrelationId(tenant, correlationId).map(List::of).orElse(List.of())
                : state != null ? flows.findByTenantIdAndStateOrderByStartedAtDesc(tenant, state)
                : flows.findTop100ByTenantIdOrderByStartedAtDesc(tenant);
        // customers see their OWN order flows — self-service tracking for
        // free; staff see the fleet
        return found.stream()
                .filter(f -> partyScope.scopedPartyId()
                        .map(own -> own.equals(f.getPartyId())).orElse(true))
                .map(f -> flowView(f, false)).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> flowById(String id) {
        ProcessFlow flow = flows.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("ProcessFlow", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(flow.getPartyId())) {
                throw NotFoundException.forResource("ProcessFlow", id);
            }
        });
        return flowView(flow, true);
    }

    /** TMF701's lever: PATCH a taskFlow — an operator completes/retries/cancels. */
    @Transactional
    public Map<String, Object> patchTask(String flowId, String taskId, Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        ProcessFlow flow = flows.findByIdAndTenantId(flowId, tenant)
                .orElseThrow(() -> NotFoundException.forResource("ProcessFlow", flowId));
        TaskFlow t = tasks.findByIdAndTenantId(taskId, tenant)
                .filter(x -> x.getProcessFlowId().equals(flow.getId()))
                .orElseThrow(() -> NotFoundException.forResource("TaskFlow", taskId));
        String state = String.valueOf(dto.get("state"));
        if (!List.of(TaskFlow.COMPLETED, TaskFlow.IN_PROGRESS, TaskFlow.FAILED).contains(state)) {
            throw new BadRequestException("state must be completed, inProgress or failed");
        }
        t.setState(state);
        t.setMessage(dto.get("message") == null ? "operator decision"
                : String.valueOf(dto.get("message")));
        if (TaskFlow.COMPLETED.equals(state)) {
            t.setCompletedAt(OffsetDateTime.now());
        }
        if (TaskFlow.IN_PROGRESS.equals(state)) {
            t.setStartedAt(OffsetDateTime.now()); // the allowance clock restarts
            t.setCompletedAt(null);
        }
        tasks.save(t);
        boolean anyOpen = tasks.findAllByTenantIdAndProcessFlowIdOrderBySeqAsc(tenant, flow.getId())
                .stream().anyMatch(x -> !TaskFlow.COMPLETED.equals(x.getState()));
        flow.setState(anyOpen ? ProcessFlow.IN_PROGRESS : ProcessFlow.COMPLETED);
        if (!anyOpen) {
            flow.setCompletedAt(OffsetDateTime.now());
        }
        flow.setMessage(anyOpen ? null : "completed by operator");
        flow.setLastUpdate(OffsetDateTime.now());
        flows.save(flow);
        publishTaskState(flow, t);
        return flowView(flow, true);
    }

    /* ---------- internals ---------- */

    private void seedIfEmpty(String tenant) {
        if (specs.findAllByTenantIdOrderByCodeAsc(tenant).isEmpty()) {
            seedDefaults(tenant);
        }
    }

    private TaskFlow current(List<TaskFlow> flowTasks) {
        return flowTasks.stream()
                .filter(t -> !TaskFlow.COMPLETED.equals(t.getState())).findFirst().orElse(null);
    }

    private void record(ProcessFlow flow, String eventType, String topic, Map<String, Object> resource) {
        ProcessEvent e = new ProcessEvent();
        e.setId(UUID.randomUUID().toString());
        e.setTenantId(flow.getTenantId());
        e.setProcessFlowId(flow.getId());
        e.setEventType(eventType);
        e.setSourceTopic(topic);
        e.setEventTime(OffsetDateTime.now());
        String digest = writeJson(resource);
        e.setDigest(digest == null ? null : digest.substring(0, Math.min(590, digest.length())));
        journal.save(e);
    }

    private void publishFlowState(ProcessFlow flow, String message) {
        events.publish("ProcessFlowStateChangeEvent", "processFlow", flowEventBody(flow, null, message));
    }

    private void publishTaskState(ProcessFlow flow, TaskFlow t) {
        events.publish("TaskFlowStateChangeEvent", "taskFlow", flowEventBody(flow, t, t.getMessage()));
    }

    private Map<String, Object> flowEventBody(ProcessFlow flow, TaskFlow t, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", t != null ? t.getId() : flow.getId());
        body.put("processFlowId", flow.getId());
        body.put("specCode", flow.getSpecCode());
        body.put("state", t != null ? t.getState() : flow.getState());
        if (t != null) {
            body.put("taskCode", t.getCode());
        }
        body.put("productOrderId", flow.getCorrelationId());
        if (message != null) {
            body.put("message", message);
        }
        if (flow.getPartyId() != null) {
            body.put("relatedParty", List.of(Map.of("id", flow.getPartyId(), "role", "customer")));
        }
        body.put("@type", t != null ? "TaskFlow" : "ProcessFlow");
        return body;
    }

    private Map<String, Object> specView(ProcessFlowSpec spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", spec.getCode());
        map.put("name", spec.getName());
        map.put("description", spec.getDescription());
        map.put("taskFlowSpecification", readTasks(spec.getTasksJson()));
        map.put("@type", "ProcessFlowSpecification");
        return map;
    }

    private Map<String, Object> flowView(ProcessFlow flow, boolean full) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", flow.getId());
        map.put("href", "/tmf-api/processFlowManagement/v4/processFlow/" + flow.getId());
        map.put("specCode", flow.getSpecCode());
        map.put("productOrderId", flow.getCorrelationId());
        map.put("state", flow.getState());
        if (flow.getMessage() != null) {
            map.put("message", flow.getMessage());
        }
        if (flow.getPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", flow.getPartyId(), "role", "customer")));
        }
        map.put("startedAt", flow.getStartedAt());
        if (flow.getCompletedAt() != null) {
            map.put("completedAt", flow.getCompletedAt());
        }
        List<Map<String, Object>> taskViews = new ArrayList<>();
        for (TaskFlow t : tasks.findAllByTenantIdAndProcessFlowIdOrderBySeqAsc(
                flow.getTenantId(), flow.getId())) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("id", t.getId());
            tv.put("code", t.getCode());
            tv.put("name", t.getName());
            tv.put("state", t.getState());
            if (t.getAllowanceSeconds() != null && t.getAllowanceSeconds() > 0) {
                tv.put("allowanceSeconds", t.getAllowanceSeconds());
            }
            if (t.getMessage() != null) {
                tv.put("message", t.getMessage());
            }
            tv.put("@type", "TaskFlow");
            taskViews.add(tv);
        }
        map.put("taskFlow", taskViews);
        if (full) {
            List<Map<String, Object>> timeline = new ArrayList<>();
            for (ProcessEvent e : journal.findAllByTenantIdAndProcessFlowIdOrderByEventTimeAsc(
                    flow.getTenantId(), flow.getId())) {
                timeline.add(Map.of("eventType", e.getEventType(), "sourceTopic",
                        String.valueOf(e.getSourceTopic()), "eventTime", e.getEventTime(),
                        "digest", String.valueOf(e.getDigest())));
            }
            map.put("timeline", timeline);
        }
        map.put("@type", "ProcessFlow");
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTasks(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
