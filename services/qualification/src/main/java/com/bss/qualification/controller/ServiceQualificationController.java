package com.bss.qualification.controller;

import com.bss.qualification.dto.AccessOptionsResult;
import com.bss.qualification.dto.CheckServiceQualificationView;
import com.bss.qualification.dto.CoverageMapRequest;
import com.bss.qualification.dto.CoverageMapView;
import com.bss.qualification.dto.QueryServiceQualificationResult;
import com.bss.qualification.dto.ServiceQualificationRequest;
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
    public ResponseEntity<CheckServiceQualificationView> check(
            @RequestBody ServiceQualificationRequest request) {
        CheckServiceQualificationView created = qualification.check(request);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @GetMapping("/checkServiceQualification/{id}")
    public ResponseEntity<CheckServiceQualificationView> findCheck(@PathVariable("id") String id) {
        return ResponseEntity.ok(qualification.findCheck(id));
    }

    @GetMapping("/checkServiceQualification")
    public ResponseEntity<List<CheckServiceQualificationView>> listChecks() {
        return ResponseEntity.ok(qualification.listChecks());
    }

    /** "What CAN you deliver here?" — the footprint, answered per place. */
    @PostMapping("/queryServiceQualification")
    public ResponseEntity<QueryServiceQualificationResult> query(
            @RequestBody ServiceQualificationRequest request) {
        return ResponseEntity.ok(qualification.query(request));
    }

    /** Open access: "which fibre OWNERS can serve this address, at what layer and
     * bandwidth?" — the wholesale shortlist a retail ISP buys from. */
    @PostMapping("/queryAccessOptions")
    public ResponseEntity<AccessOptionsResult> accessOptions(
            @RequestBody ServiceQualificationRequest request) {
        return ResponseEntity.ok(qualification.accessOptions(request));
    }

    /* ---- the footprint as data: operator CRUD ---- */

    @GetMapping("/coverageMap")
    public ResponseEntity<List<CoverageMapView>> listCoverage() {
        return ResponseEntity.ok(coverage.findAll());
    }

    @PostMapping("/coverageMap")
    public ResponseEntity<CoverageMapView> createCoverage(@RequestBody CoverageMapRequest dto) {
        CoverageMapView created = coverage.create(dto);
        return ResponseEntity.created(URI.create(created.href())).body(created);
    }

    @DeleteMapping("/coverageMap/{id}")
    public ResponseEntity<Void> deleteCoverage(@PathVariable("id") String id) {
        coverage.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
