package com.bss.intelligence.controller;

import com.bss.intelligence.service.Tmf915Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * TMF915 AI Management: the control plane, standards-addressable. Models
 * are what the audit ledger proves has served; contracts are the governed
 * scenarios with their real monitoring numbers. Reads are ops-grade
 * (ai:use); the PATCH — suspending one contract — is the first door behind
 * the new ai:admin authority, the seam the governance controller always
 * said production would need.
 */
@RestController
@RequestMapping("/tmf-api/aiManagement/v4")
public class Tmf915Controller {

    private final Tmf915Service service;

    public Tmf915Controller(Tmf915Service service) {
        this.service = service;
    }

    @GetMapping("/aiModel")
    public ResponseEntity<List<Map<String, Object>>> models() {
        return ResponseEntity.ok(service.listModels());
    }

    @GetMapping("/aiModelContract")
    public ResponseEntity<List<Map<String, Object>>> contracts() {
        return ResponseEntity.ok(service.listContracts());
    }

    @GetMapping("/aiModelContract/{id}")
    public ResponseEntity<Map<String, Object>> contract(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findContract(id));
    }

    /** The in-life lever: {state: suspended|active, note} — ai:admin only. */
    @PatchMapping("/aiModelContract/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable("id") String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchContract(id, patch));
    }
}
