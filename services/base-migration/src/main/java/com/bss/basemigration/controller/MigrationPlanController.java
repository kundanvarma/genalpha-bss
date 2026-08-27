package com.bss.basemigration.controller;

import com.bss.basemigration.api.ApiConstants;
import com.bss.basemigration.api.PagedResult;
import com.bss.basemigration.service.MigrationPlanService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The migration desk's door. Reads are migration:read; every mutation is
 * migration:admin (enforced in SecurityConfig). The lifecycle endpoints
 * mirror the plan states: attachSimulation (the rehearsal gate), arm,
 * pause/resume, scanTriggers; per-customer, the exercised exit and the
 * snapshot rollback.
 */
@RestController
@Validated
@RequestMapping(ApiConstants.BASE_PATH)
public class MigrationPlanController {

    private final MigrationPlanService service;

    public MigrationPlanController(MigrationPlanService service) {
        this.service = service;
    }

    @PostMapping("/migrationPlan")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/migrationPlan")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        PagedResult<Map<String, Object>> result = service.list(offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @GetMapping("/migrationPlan/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/migrationPlan/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patch(id, dto));
    }

    @DeleteMapping("/migrationPlan/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/migrationPlan/{id}/attachSimulation")
    public ResponseEntity<Map<String, Object>> attachSimulation(@PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.attachSimulation(id, body));
    }

    @PostMapping("/migrationPlan/{id}/arm")
    public ResponseEntity<Map<String, Object>> arm(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.arm(id));
    }

    @PostMapping("/migrationPlan/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.pause(id));
    }

    @PostMapping("/migrationPlan/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.resume(id));
    }

    @PostMapping("/migrationPlan/{id}/scanTriggers")
    public ResponseEntity<Map<String, Object>> scanTriggers(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.scanTriggers(id));
    }

    @GetMapping("/migrationPlan/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.progress(id));
    }

    @GetMapping("/migrationPlan/{id}/customer")
    public ResponseEntity<List<Map<String, Object>>> customers(@PathVariable("id") String id,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(200) int limit) {
        PagedResult<Map<String, Object>> result = service.customers(id, state, offset, limit);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalCount()))
                .header("X-Result-Count", String.valueOf(result.items().size()))
                .body(result.items());
    }

    @PostMapping("/migrationPlan/{id}/customer/{cid}/exit")
    public ResponseEntity<Map<String, Object>> exit(@PathVariable("id") String id,
            @PathVariable("cid") String cid) {
        return ResponseEntity.ok(service.exit(id, cid));
    }

    @PostMapping("/migrationPlan/{id}/customer/{cid}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable("id") String id,
            @PathVariable("cid") String cid) {
        return ResponseEntity.ok(service.rollback(id, cid));
    }
}
