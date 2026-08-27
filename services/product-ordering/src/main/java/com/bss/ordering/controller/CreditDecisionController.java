package com.bss.ordering.controller;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.credit.CreditDecisionService;
import com.bss.ordering.credit.MockBureauDriver;
import com.bss.ordering.exception.OrderValidationException;
import com.bss.ordering.security.PartyScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Back-office face of the credit seam: the stored decisions for a party
 * (never a report — there is none to show), and the deterministic mock
 * lever the demos/e2e suites use to pin an outcome for a test identity.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/creditDecision")
public class CreditDecisionController {

    private final CreditDecisionService service;
    private final Optional<MockBureauDriver> mockDriver;
    private final PartyScope partyScope;

    public CreditDecisionController(CreditDecisionService service,
            Optional<MockBureauDriver> mockDriver, PartyScope partyScope) {
        this.service = service;
        this.mockDriver = mockDriver;
        this.partyScope = partyScope;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(name = "relatedPartyId") String relatedPartyId) {
        requireBackOffice();
        return ResponseEntity.ok(service.decisionsFor(relatedPartyId));
    }

    /** {ref, decision: approve|review|decline|frozen (empty clears)}. */
    @PostMapping("/mockOutcome")
    public ResponseEntity<Map<String, Object>> mockOutcome(@RequestBody Map<String, Object> body) {
        requireBackOffice();
        MockBureauDriver driver = mockDriver.orElseThrow(
                () -> new OrderValidationException("no mock bureau driver in this deployment"));
        String ref = body == null || body.get("ref") == null ? null : String.valueOf(body.get("ref"));
        if (ref == null || ref.isBlank()) {
            throw new OrderValidationException("ref is required");
        }
        String decision = body.get("decision") == null ? null : String.valueOf(body.get("decision"));
        driver.setOutcome(ref, decision);
        return ResponseEntity.ok(Map.of("ref", ref, "decision", decision == null ? "cleared" : decision));
    }

    private void requireBackOffice() {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new OrderValidationException("credit administration is a back-office operation");
        }
    }
}
