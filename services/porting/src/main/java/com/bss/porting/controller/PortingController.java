package com.bss.porting.controller;

import com.bss.porting.api.ApiConstants;
import com.bss.porting.service.PortingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class PortingController {

    private final PortingService service;

    public PortingController(PortingService service) {
        this.service = service;
    }

    @PostMapping("/numberPortingOrder")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.create(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/numberPortingOrder")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(service.findAll(relatedPartyId, status));
    }

    @GetMapping("/numberPortingOrder/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/numberPortingOrder/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String id) {
        return ResponseEntity.ok(service.complete(id));
    }

    /** Internal seam for the orchestrator: the number this party ported in. */
    @GetMapping("/portedNumber")
    public ResponseEntity<Map<String, Object>> portedNumber(@RequestParam String relatedPartyId) {
        return ResponseEntity.ok(service.portedNumberFor(relatedPartyId));
    }
}
