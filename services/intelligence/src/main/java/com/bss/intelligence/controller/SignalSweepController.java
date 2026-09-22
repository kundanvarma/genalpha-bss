package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.signal.SignalClassifier;
import com.bss.intelligence.signal.SweepResults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** On-demand signal-classification sweep (SI-P3) — demos and tests don't
 * wait for the schedule; the churnSweep pattern. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class SignalSweepController {

    private final SignalClassifier classifier;
    private final com.bss.intelligence.signal.CanaryProbe canaryProbe;

    public SignalSweepController(SignalClassifier classifier,
            com.bss.intelligence.signal.CanaryProbe canaryProbe) {
        this.classifier = classifier;
        this.canaryProbe = canaryProbe;
    }

    @PostMapping("/signalSweep")
    public ResponseEntity<SweepResults.SignalSweepResult> sweep() {
        return ResponseEntity.ok(classifier.sweepAllTenants());
    }

    /** T-P4: probe the provider with stored canaries — on demand. */
    @PostMapping("/canaryProbe")
    public ResponseEntity<SweepResults.CanaryProbeResult> canaryProbe() {
        return ResponseEntity.ok(canaryProbe.probeCurrentTenant(3));
    }
}
