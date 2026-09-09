package com.bss.catalog.controller;

import com.bss.catalog.api.ApiConstants;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.service.LaunchGovernanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    public List<Map<String, Object>> queue() {
        return governance.queue();
    }

    @GetMapping("/governance/settings")
    public Map<String, Object> settings() {
        return Map.of("mode", governance.mode(), "aiProposals", governance.aiProposals(),
                "readiness", governance.readinessTemplate(), "canApprove", governance.approver(),
                "channels", com.bss.catalog.service.Channels.REGISTERED);
    }

    /** Would this (possibly unsaved) offer launch by itself? The envelope dry-run. */
    @PostMapping("/governance/dry-run")
    public Map<String, Object> dryRun(@RequestBody ProductOfferingDto dto) {
        return governance.dryRun(dto);
    }

    @GetMapping("/productOffering/{id}/governance")
    public Map<String, Object> view(@PathVariable String id) {
        return governance.view(id);
    }

    @PostMapping("/productOffering/{id}/governance/request")
    public ResponseEntity<Map<String, Object>> request(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(governance.request(id, body == null ? Map.of() : body));
    }

    @PostMapping("/productOffering/{id}/governance/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return governance.approve(id, body == null ? Map.of() : body);
    }

    @PostMapping("/productOffering/{id}/governance/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return governance.reject(id, body == null ? Map.of() : body);
    }

    @PostMapping("/productOffering/{id}/governance/hold")
    public Map<String, Object> hold(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return governance.hold(id, body == null ? Map.of() : body);
    }

    @PostMapping("/productOffering/{id}/governance/resume")
    public Map<String, Object> resume(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return governance.resume(id, body == null ? Map.of() : body);
    }

    @PostMapping("/productOffering/{id}/governance/ready")
    public Map<String, Object> ready(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return governance.ready(id, body);
    }

    @PostMapping("/productOffering/{id}/governance/launch")
    public Map<String, Object> launch(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return governance.launch(id, body == null ? Map.of() : body);
    }

    @PostMapping("/productOffering/{id}/governance/unlaunch")
    public Map<String, Object> unlaunch(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return governance.unlaunch(id, body == null ? Map.of() : body);
    }
}
