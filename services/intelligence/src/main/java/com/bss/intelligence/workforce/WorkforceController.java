package com.bss.intelligence.workforce;

import com.bss.intelligence.api.ApiConstants;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The digital workforce API — what a badged worker (Hermes or any
 * MCP-speaking agent runtime) drives:
 *
 *   GET  /ai/v1/workforce/tasks               the open queue (derived live)
 *   POST /ai/v1/workforce/tasks/{id}/claim    lease it
 *   POST /ai/v1/workforce/tasks/{id}/complete verified completion + outcome
 *   POST /ai/v1/workforce/tasks/{id}/escalate hand it to a human, counted
 *   GET  /ai/v1/workforce/ledger              the shift record
 *
 * Gated by the workforce:use authority — granted only inside the
 * digital-worker role bundle: THE BADGE IS THE OPT-IN SWITCH.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/workforce")
public class WorkforceController {

    private final WorkforceService service;
    private final WorkforceApprovalService approvalService;
    private final WorkforceKpiService kpiService;

    public WorkforceController(WorkforceService service,
            WorkforceApprovalService approvalService, WorkforceKpiService kpiService) {
        this.service = service;
        this.approvalService = approvalService;
        this.kpiService = kpiService;
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return service.openTasks();
    }

    @PostMapping("/tasks/{id}/claim")
    public Map<String, Object> claim(@PathVariable("id") String id) {
        return service.claim(id);
    }

    @PostMapping("/tasks/{id}/complete")
    public Map<String, Object> complete(@PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.complete(id, body);
    }

    @PostMapping("/tasks/{id}/escalate")
    public Map<String, Object> escalate(@PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.escalate(id, body);
    }

    @GetMapping("/ledger")
    public List<Map<String, Object>> ledger() {
        return service.ledger();
    }

    /* ---- the T3 gate: workers FILE, humans DECIDE ---- */

    @PostMapping("/approvals")
    public Map<String, Object> fileApproval(@RequestBody Map<String, Object> body) {
        return approvalService.file(body);
    }

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(
            @org.springframework.web.bind.annotation.RequestParam(name = "status", required = false)
            String status) {
        return approvalService.list(status);
    }

    /** The approver's OWN token executes the stored action. */
    @PostMapping("/approvals/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") String id,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Authorization",
                    required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        return approvalService.approve(id, authorization, body);
    }

    @PostMapping("/approvals/{id}/refuse")
    public Map<String, Object> refuse(@PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return approvalService.refuse(id, body);
    }

    /* ---- the scoreboard ---- */

    @GetMapping("/kpis")
    public Map<String, Object> kpis() {
        return kpiService.kpis();
    }
}
