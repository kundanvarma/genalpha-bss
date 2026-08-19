package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.signal.CltvScorer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** On-demand CLTV sweep (SI-P4) — the churnSweep pattern. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class CltvController {

    private final CltvScorer scorer;

    public CltvController(CltvScorer scorer) {
        this.scorer = scorer;
    }

    @PostMapping("/cltvSweep")
    public ResponseEntity<Map<String, Object>> sweep() {
        return ResponseEntity.ok(scorer.sweepAllTenants());
    }
}
