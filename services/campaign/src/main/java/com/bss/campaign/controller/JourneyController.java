package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.dto.ArbitrationDecisionView;
import com.bss.campaign.dto.ConversionReceipt;
import com.bss.campaign.dto.ConversionRequest;
import com.bss.campaign.dto.EnrollmentReceipt;
import com.bss.campaign.dto.EnrollmentRequest;
import com.bss.campaign.dto.JourneyRequest;
import com.bss.campaign.dto.JourneyStats;
import com.bss.campaign.dto.JourneyView;
import com.bss.campaign.dto.SegmentEnrollmentReceipt;
import com.bss.campaign.dto.TuneResult;
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

@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/journey")
public class JourneyController {

    private final JourneyService service;

    public JourneyController(JourneyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JourneyView> create(@RequestBody JourneyRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<JourneyView>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<JourneyView> patch(@PathVariable String id, @RequestBody JourneyRequest patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** The NBA receipt log: every next-best-action decision, newest first. */
    @GetMapping("/arbitrationDecisions")
    public ResponseEntity<List<ArbitrationDecisionView>> arbitrationDecisions(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String partyId) {
        return ResponseEntity.ok(service.arbitrationDecisions(partyId));
    }

    /** Enroll everyone in the journey's insight segment, once. */
    @PostMapping("/{id}/enroll")
    public ResponseEntity<SegmentEnrollmentReceipt> enroll(@PathVariable String id) {
        return ResponseEntity.ok(service.enrollSegment(id));
    }

    /** Enrol a list of parties by hand: an offline list, a store's walk-ins, a test cohort. */
    @PostMapping("/{id}/enrollments")
    public ResponseEntity<EnrollmentReceipt> enrollParties(@PathVariable String id,
            @RequestBody EnrollmentRequest body) {
        return ResponseEntity.ok(service.enrollParties(id, body.partyIds(), body.context()));
    }

    /** Record a conversion that did not arrive as an event (a store sale, a call-centre close). */
    @PostMapping("/{id}/conversion")
    public ResponseEntity<ConversionReceipt> conversion(@PathVariable String id,
            @RequestBody ConversionRequest body) {
        return ResponseEntity.ok(service.recordConversion(id, body.partyId(), body.value()));
    }

    /** Judge the A/B arms now and shift traffic if the evidence is there — the same rule the clock runs. */
    @PostMapping("/{id}/tune")
    public ResponseEntity<TuneResult> tune(@PathVariable String id) {
        return ResponseEntity.ok(service.tune(id));
    }

    /** The funnel: entered / at each step / converted per variant / lift. */
    @GetMapping("/{id}/stats")
    public ResponseEntity<JourneyStats> stats(@PathVariable String id) {
        return ResponseEntity.ok(service.statsOf(id));
    }
}
