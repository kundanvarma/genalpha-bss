package com.bss.campaign.controller;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.service.JourneyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /** Enroll everyone in the journey's insight segment, once. */
    @PostMapping("/{id}/enroll")
    public ResponseEntity<Map<String, Object>> enroll(@PathVariable String id) {
        return ResponseEntity.ok(service.enrollSegment(id));
    }

    /** The funnel: entered / at each step / converted per variant / lift. */
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable String id) {
        return ResponseEntity.ok(service.statsOf(id));
    }
}
