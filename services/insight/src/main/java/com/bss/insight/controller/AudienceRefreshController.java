package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.schedule.AudienceRefreshScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(scheduler.status());
    }

    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause() {
        scheduler.pause();
        return ResponseEntity.ok(scheduler.status());
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume() {
        scheduler.resume();
        return ResponseEntity.ok(scheduler.status());
    }

    /** Manual sweep — an ops action, runs even while paused. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        int n = scheduler.runSweep();
        Map<String, Object> out = new LinkedHashMap<>(scheduler.status());
        out.put("refreshed", n);
        return ResponseEntity.ok(out);
    }
}
