package com.bss.quote.controller;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.service.SalesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.SALES_BASE)
public class SalesController {

    private final SalesService service;

    public SalesController(SalesService service) {
        this.service = service;
    }

    /** Open capture: a prospect (or any channel) may knock without a token. */
    @PostMapping("/salesLead")
    public ResponseEntity<Map<String, Object>> createLead(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = service.createLead(dto);
        return ResponseEntity.created(URI.create(String.valueOf(created.get("href")))).body(created);
    }

    @GetMapping("/salesLead")
    public ResponseEntity<List<Map<String, Object>>> leads() {
        return ResponseEntity.ok(service.findLeads());
    }

    @GetMapping("/salesLead/{id}")
    public ResponseEntity<Map<String, Object>> lead(@PathVariable String id) {
        return ResponseEntity.ok(service.findLead(id));
    }

    /** qualified (mints the opportunity) or unqualified — once. */
    @PatchMapping("/salesLead/{id}")
    public ResponseEntity<Map<String, Object>> patchLead(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchLead(id, patch));
    }

    @GetMapping("/salesOpportunity")
    public ResponseEntity<List<Map<String, Object>>> opportunities() {
        return ResponseEntity.ok(service.findOpportunities());
    }

    @GetMapping("/salesOpportunity/{id}")
    public ResponseEntity<Map<String, Object>> opportunity(@PathVariable String id) {
        return ResponseEntity.ok(service.findOpportunity(id));
    }

    /** won (optionally with the quote that sealed it) or lost — once. */
    @PatchMapping("/salesOpportunity/{id}")
    public ResponseEntity<Map<String, Object>> patchOpportunity(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patchOpportunity(id, patch));
    }
}
