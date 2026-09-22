package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.decision.DecisionPoints;
import com.bss.campaign.dto.DecisionPointView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The policy registry: which DecisionPoints this service has and what answers each today. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/decisionPoint")
public class DecisionPointController {

    private final DecisionPoints decisions;

    public DecisionPointController(DecisionPoints decisions) {
        this.decisions = decisions;
    }

    @GetMapping
    public ResponseEntity<List<DecisionPointView>> registry() {
        return ResponseEntity.ok(decisions.registryView());
    }
}
