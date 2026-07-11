package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.churn.ChurnModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ChurnLearningController {

    private final ChurnModelService service;

    public ChurnLearningController(ChurnModelService service) {
        this.service = service;
    }

    /** Ground truth in: this customer left (or provably stayed). */
    @PostMapping("/churnOutcome")
    public ResponseEntity<Map<String, Object>> outcome(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.recordOutcome(request));
    }

    /** Fit from what this deployment has lived through. */
    @PostMapping("/churnTrain")
    public ResponseEntity<Map<String, Object>> train() {
        return ResponseEntity.ok(service.trainFromHistory());
    }

    /** Fit from the operator's historical data — production quality on day one. */
    @PostMapping("/churnTraining/import")
    public ResponseEntity<Map<String, Object>> importAndTrain(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(service.trainFromImport(request));
    }

    @GetMapping("/churnModel")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(service.status());
    }
}
