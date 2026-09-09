package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.service.JourneyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/journey")
public class JourneyController {

    private final JourneyService service;

    public JourneyController(JourneyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** The NBA receipt log: every next-best-action decision, newest first. */
    @GetMapping("/arbitrationDecisions")
    public ResponseEntity<List<Map<String, Object>>> arbitrationDecisions(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String partyId) {
        return ResponseEntity.ok(service.arbitrationDecisions(partyId));
    }

    /** Enroll everyone in the journey's insight segment, once. */
    @PostMapping("/{id}/enroll")
    public ResponseEntity<Map<String, Object>> enroll(@PathVariable String id) {
        return ResponseEntity.ok(service.enrollSegment(id));
    }

    /** Enrol a list of parties by hand: an offline list, a store's walk-ins, a test cohort. */
    @PostMapping("/{id}/enrollments")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> enrollParties(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        java.util.List<String> ids = body.get("partyIds") instanceof java.util.List<?> l
                ? l.stream().map(String::valueOf).toList() : java.util.List.of();
        Map<String, Object> context = body.get("context") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return ResponseEntity.ok(service.enrollParties(id, ids, context));
    }

    /** Record a conversion that did not arrive as an event (a store sale, a call-centre close). */
    @PostMapping("/{id}/conversion")
    public ResponseEntity<Map<String, Object>> conversion(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        java.math.BigDecimal value = body.get("value") == null ? null : new java.math.BigDecimal(String.valueOf(body.get("value")));
        return ResponseEntity.ok(service.recordConversion(id, String.valueOf(body.get("partyId")), value));
    }

    /** Judge the A/B arms now and shift traffic if the evidence is there — the same rule the clock runs. */
    @PostMapping("/{id}/tune")
    public ResponseEntity<Map<String, Object>> tune(@PathVariable String id) {
        return ResponseEntity.ok(service.tune(id));
    }

    /** The funnel: entered / at each step / converted per variant / lift. */
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable String id) {
        return ResponseEntity.ok(service.statsOf(id));
    }
}
