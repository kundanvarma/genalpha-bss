package com.bss.usage.controller;

import com.bss.usage.service.UsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The OCS → BSS notification door. The Online Charging System calls this when a
 * subscriber crosses a usage threshold ("running low"); we relay it onto the
 * event bus for the growth engine. Internal-only (no gateway route, permitAll
 * in SecurityConfig): the OCS reaches it by service name on the private network,
 * exactly as a real OCS reaches a BSS notification endpoint. Not TMF-facing —
 * hence /internal, kept off the /tmf-api surface and its CTKs.
 */
@RestController
@RequestMapping("/internal/ocs")
public class OcsNotificationController {

    private final UsageService service;

    public OcsNotificationController(UsageService service) {
        this.service = service;
    }

    @PostMapping("/usageThreshold")
    public ResponseEntity<Map<String, Object>> usageThreshold(@RequestBody Map<String, Object> body) {
        service.notifyUsageThreshold(body);
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }
}
