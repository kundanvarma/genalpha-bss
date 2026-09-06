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

    /** Slice-aware charging: the OCS reports GB that rode the PRIORITY slice; the
     * BSS rates the uplift as its own line ("Priority data") on the next bill. */
    @PostMapping("/priorityUsage")
    public ResponseEntity<Map<String, Object>> priorityUsage(@RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().body(service.recordPriorityUsage(body));
    }

    /** The monetary sibling: an external charging edge reports a spend
     * accrual {partyId, chargeClass, amount}; the reply's accepted=false
     * tells it to refuse the charge (barring, cap, roaming cut-off). */
    @PostMapping("/spendThreshold")
    public ResponseEntity<Map<String, Object>> spendThreshold(@RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().body(service.notifySpendThreshold(body));
    }
}
