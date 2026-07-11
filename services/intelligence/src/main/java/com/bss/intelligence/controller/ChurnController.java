package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.churn.ChurnScorer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ChurnController {

    private final ChurnScorer scorer;

    public ChurnController(ChurnScorer scorer) {
        this.scorer = scorer;
    }

    /** On-demand sweep — demos and tests don't wait for the schedule. */
    @PostMapping("/churnSweep")
    public ResponseEntity<Map<String, Object>> sweep() {
        return ResponseEntity.ok(scorer.sweepAllTenants());
    }
}
