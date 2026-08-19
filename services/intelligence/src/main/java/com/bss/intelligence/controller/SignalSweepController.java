package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.signal.SignalClassifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** On-demand signal-classification sweep (SI-P3) — demos and tests don't
 * wait for the schedule; the churnSweep pattern. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class SignalSweepController {

    private final SignalClassifier classifier;

    public SignalSweepController(SignalClassifier classifier) {
        this.classifier = classifier;
    }

    @PostMapping("/signalSweep")
    public ResponseEntity<Map<String, Object>> sweep() {
        return ResponseEntity.ok(classifier.sweepAllTenants());
    }
}
