package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.decision.DecisionLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The decision log: what the BSS chose, why, and what followed. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/decisions")
public class DecisionController {

    private final DecisionLogService service;

    public DecisionController(DecisionLogService service) {
        this.service = service;
    }

    /** Newest first; filter by decision point and/or subject (a party, a journey, an offering). */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String decisionPoint,
            @RequestParam(required = false) String subjectId, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.list(decisionPoint, subjectId, limit));
    }

    /** Per decision point: volume, policy in force, propensity coverage, outcome rate. */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(service.summary());
    }

    /** The receipt for one decision. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> receipt(@PathVariable String id) {
        Map<String, Object> r = service.receipt(id);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }
}
