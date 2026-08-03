package com.bss.qualification.controller;

import com.bss.qualification.service.CoverageMapService;
import com.bss.qualification.service.ServiceQualificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * TMF645 Service Qualification: the technical shop window. The two task
 * resources are anonymous (a prospect asks "what can I get here?" before
 * having any identity); a persisted check reads back by its unguessable id.
 * The LIST of checks and the coverage-map CRUD are back-office — a
 * qualification carries a customer's address, and the raw footprint rows
 * are operator data.
 */
@RestController
@RequestMapping("/tmf-api/serviceQualificationManagement/v4")
public class ServiceQualificationController {

    private final ServiceQualificationService qualification;
    private final CoverageMapService coverage;

    public ServiceQualificationController(ServiceQualificationService qualification,
            CoverageMapService coverage) {
        this.qualification = qualification;
        this.coverage = coverage;
    }

    /** "Can you deliver THIS here?" — verdicts kept, alternative proposed. */
    @PostMapping("/checkServiceQualification")
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, Object> request) {
        Map<String, Object> created = qualification.check(request);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/checkServiceQualification/{id}")
    public ResponseEntity<Map<String, Object>> findCheck(@PathVariable("id") String id) {
        return ResponseEntity.ok(qualification.findCheck(id));
    }

    @GetMapping("/checkServiceQualification")
    public ResponseEntity<List<Map<String, Object>>> listChecks() {
        return ResponseEntity.ok(qualification.listChecks());
    }

    /** "What CAN you deliver here?" — the footprint, answered per place. */
    @PostMapping("/queryServiceQualification")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(qualification.query(request));
    }

    /* ---- the footprint as data: operator CRUD ---- */

    @GetMapping("/coverageMap")
    public ResponseEntity<List<Map<String, Object>>> listCoverage() {
        return ResponseEntity.ok(coverage.findAll());
    }

    @PostMapping("/coverageMap")
    public ResponseEntity<Map<String, Object>> createCoverage(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = coverage.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @DeleteMapping("/coverageMap/{id}")
    public ResponseEntity<Void> deleteCoverage(@PathVariable("id") String id) {
        coverage.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
