package com.bss.catalog.controller;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.dto.GovernanceRequest;
import com.bss.catalog.dto.GovernanceSettings;
import com.bss.catalog.dto.LaunchDecision;
import com.bss.catalog.dto.LaunchDryRun;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.service.Channels;
import com.bss.catalog.service.LaunchGovernanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The launch-governance doors. They hang beside the TMF620 resource (same base
 * path, so the gateway route and the tenant resolution already cover them) and
 * never change its shape: a ProductOffering stays a ProductOffering, the
 * approval trail lives here.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class GovernanceController {

    private final LaunchGovernanceService governance;

    public GovernanceController(LaunchGovernanceService governance) {
        this.governance = governance;
    }

    @GetMapping("/governance/queue")
    public List<LaunchDecision> queue() {
        return governance.queue();
    }

    @GetMapping("/governance/settings")
    public GovernanceSettings settings() {
        return new GovernanceSettings(governance.mode(), governance.aiProposals(), governance.readinessTemplate(),
                governance.approver(), Channels.REGISTERED);
    }

    /** Would this (possibly unsaved) offer launch by itself? The envelope dry-run. */
    @PostMapping("/governance/dry-run")
    public LaunchDryRun dryRun(@RequestBody ProductOfferingDto dto) {
        return governance.dryRun(dto);
    }

    @GetMapping("/productOffering/{id}/governance")
    public LaunchDecision view(@PathVariable String id) {
        return governance.view(id);
    }

    @PostMapping("/productOffering/{id}/governance/request")
    public ResponseEntity<LaunchDecision> request(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return ResponseEntity.ok(governance.request(id, orEmpty(body)));
    }

    @PostMapping("/productOffering/{id}/governance/approve")
    public LaunchDecision approve(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return governance.approve(id, orEmpty(body));
    }

    @PostMapping("/productOffering/{id}/governance/reject")
    public LaunchDecision reject(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return governance.reject(id, orEmpty(body));
    }

    @PostMapping("/productOffering/{id}/governance/hold")
    public LaunchDecision hold(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return governance.hold(id, orEmpty(body));
    }

    @PostMapping("/productOffering/{id}/governance/resume")
    public LaunchDecision resume(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return governance.resume(id, orEmpty(body));
    }

    @PostMapping("/productOffering/{id}/governance/ready")
    public LaunchDecision ready(@PathVariable String id, @RequestBody GovernanceRequest body) {
        return governance.ready(id, body);
    }

    @PostMapping("/productOffering/{id}/governance/launch")
    public LaunchDecision launch(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return governance.launch(id, orEmpty(body));
    }

    @PostMapping("/productOffering/{id}/governance/unlaunch")
    public LaunchDecision unlaunch(@PathVariable String id, @RequestBody(required = false) GovernanceRequest body) {
        return governance.unlaunch(id, orEmpty(body));
    }

    private static GovernanceRequest orEmpty(GovernanceRequest body) {
        return body == null ? GovernanceRequest.EMPTY : body;
    }
}
