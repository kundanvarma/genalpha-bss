package com.bss.intelligence.controller;

import com.bss.intelligence.risk.RiskService;
import org.springframework.http.ResponseEntity;
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
 * TMF696 Risk Management: transparent, persisted assessments computed
 * from what the fleet's data actually knows. Back-office only
 * (risk:assess) — a risk score is a credit check, not a customer
 * surface; the ordering service holds the same authority to fetch a
 * score for its policy context.
 */
@RestController
@RequestMapping("/tmf-api/riskManagement/v4")
public class Tmf696Controller {

    private final RiskService service;

    public Tmf696Controller(RiskService service) {
        this.service = service;
    }

    @PostMapping("/partyRiskAssessment")
    public ResponseEntity<Map<String, Object>> assessParty(@RequestBody Map<String, Object> request) {
        Map<String, Object> created = service.assessParty(request);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @PostMapping("/productOrderRiskAssessment")
    public ResponseEntity<Map<String, Object>> assessOrder(@RequestBody Map<String, Object> request) {
        Map<String, Object> created = service.assessOrder(request);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping({"/partyRiskAssessment/{id}", "/productOrderRiskAssessment/{id}"})
    public ResponseEntity<Map<String, Object>> byId(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.find(id));
    }

    @GetMapping({"/partyRiskAssessment", "/productOrderRiskAssessment"})
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.list());
    }
}
