package com.bss.billing.controller;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.service.CollectionService;
import org.springframework.http.ResponseEntity;
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
 * Collections: cases (staff see the worklist, customers their own case),
 * promise-to-pay, holds, write-off, and the dunning policy with its
 * statutory floor. Cases are made by the sweeper, never POSTed.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class CollectionController {

    private final CollectionService service;
    private final com.bss.billing.security.TenantScope tenantScope;

    public CollectionController(CollectionService service,
            com.bss.billing.security.TenantScope tenantScope) {
        this.service = service;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/collectionCase")
    public ResponseEntity<List<Map<String, Object>>> cases(
            @RequestParam(name = "state", required = false) String state) {
        return ResponseEntity.ok(service.findCases(state));
    }

    @GetMapping("/collectionCase/{id}")
    public ResponseEntity<Map<String, Object>> caseById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findCase(id));
    }

    /** "I will pay by Friday" — pauses the ladder within the allowance. */
    @PostMapping("/collectionCase/{id}/promiseToPay")
    public ResponseEntity<Map<String, Object>> promiseToPay(@PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return ResponseEntity.ok(service.promiseToPay(id, dto == null ? Map.of() : dto));
    }

    @PostMapping("/collectionCase/{id}/hold")
    public ResponseEntity<Map<String, Object>> hold(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.hold(id, dto));
    }

    @PostMapping("/collectionCase/{id}/release")
    public ResponseEntity<Map<String, Object>> release(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.release(id, dto));
    }

    @PostMapping("/collectionCase/{id}/writeOff")
    public ResponseEntity<Map<String, Object>> writeOff(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.writeOff(id, dto));
    }

    /** Walk THIS tenant's ladder now — the operator's (and proof run's)
     * alternative to waiting for the scheduled tick. */
    @PostMapping("/collectionSweep")
    public ResponseEntity<Map<String, Object>> sweepNow() {
        String tenantId = tenantScope.currentTenantId();
        service.sweepTenant(tenantId);
        return ResponseEntity.ok(Map.of("swept", tenantId));
    }

    // ---- dunning policy ----

    @GetMapping("/dunningPolicy")
    public ResponseEntity<List<Map<String, Object>>> policies() {
        return ResponseEntity.ok(service.findPolicies());
    }

    @GetMapping("/dunningPolicy/{id}")
    public ResponseEntity<Map<String, Object>> policy(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findPolicy(id));
    }

    @PostMapping("/dunningPolicy")
    public ResponseEntity<Map<String, Object>> createPolicy(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createPolicy(dto);
        return ResponseEntity.created(URI.create(
                ApiConstants.BASE_PATH + "/dunningPolicy/" + created.get("id"))).body(created);
    }

    @PatchMapping("/dunningPolicy/{id}")
    public ResponseEntity<Map<String, Object>> patchPolicy(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(service.patchPolicy(id, dto));
    }
}
