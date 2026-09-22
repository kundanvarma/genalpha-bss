package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.churn.ChurnLearning;
import com.bss.intelligence.churn.ChurnModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ChurnLearningController {

    private final ChurnModelService service;

    public ChurnLearningController(ChurnModelService service) {
        this.service = service;
    }

    /** Ground truth in: this customer left (or provably stayed). */
    @PostMapping("/churnOutcome")
    public ResponseEntity<ChurnLearning.OutcomeReceipt> outcome(
            @RequestBody ChurnLearning.RecordOutcomeRequest request) {
        return ResponseEntity.ok(service.recordOutcome(request));
    }

    /** Fit from what this deployment has lived through. */
    @PostMapping("/churnTrain")
    public ResponseEntity<ChurnLearning.TrainingResult> train() {
        return ResponseEntity.ok(service.trainFromHistory());
    }

    /** Fit from the operator's historical data — production quality on day one. */
    @PostMapping("/churnTraining/import")
    public ResponseEntity<ChurnLearning.TrainingResult> importAndTrain(
            @RequestBody ChurnLearning.TrainFromImportRequest request) {
        return ResponseEntity.ok(service.trainFromImport(request));
    }

    @GetMapping("/churnModel")
    public ResponseEntity<ChurnLearning.ChurnModelStatus> status() {
        return ResponseEntity.ok(service.status());
    }
}
