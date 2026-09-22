package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.RefreshStatus;
import com.bss.insight.schedule.AudienceRefreshScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops surface for the auto-refresh scheduler: see what it's doing (activity +
 * JVM heap), pause/resume it at runtime (no restart needed), or trigger a run.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/refresh")
public class AudienceRefreshController {

    private final AudienceRefreshScheduler scheduler;

    public AudienceRefreshController(AudienceRefreshScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @GetMapping("/status")
    public ResponseEntity<RefreshStatus> status() {
        return ResponseEntity.ok(scheduler.status());
    }

    @PostMapping("/pause")
    public ResponseEntity<RefreshStatus> pause() {
        scheduler.pause();
        return ResponseEntity.ok(scheduler.status());
    }

    @PostMapping("/resume")
    public ResponseEntity<RefreshStatus> resume() {
        scheduler.resume();
        return ResponseEntity.ok(scheduler.status());
    }

    /** Manual sweep — an ops action, runs even while paused. */
    @PostMapping("/run")
    public ResponseEntity<RefreshStatus.RunReceipt> run() {
        int n = scheduler.runSweep();
        return ResponseEntity.ok(new RefreshStatus.RunReceipt(scheduler.status(), n));
    }
}
