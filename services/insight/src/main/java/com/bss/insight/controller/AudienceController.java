package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.service.AudienceService;
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

/** Saved audiences: author a criteria tree, list them, and resolve members. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/audience")
public class AudienceController {

    private final AudienceService service;
    private final com.bss.insight.service.ActivationService activation;

    public AudienceController(AudienceService service, com.bss.insight.service.ActivationService activation) {
        this.service = service;
        this.activation = activation;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean explain,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean snapshot) {
        if (Boolean.TRUE.equals(snapshot)) {
            return ResponseEntity.ok(service.snapshotMembers(id)); // frozen set, instant
        }
        return Boolean.TRUE.equals(explain)
                ? ResponseEntity.ok(service.membersExplain(id))
                : ResponseEntity.ok(service.members(id));
    }

    /** Materialize the audience — freeze its members into a snapshot. */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String id) {
        return ResponseEntity.ok(service.refresh(id));
    }

    /** The BSS traits this tenant holds — real key/value choices for a builder. */
    @GetMapping("/facets")
    public ResponseEntity<List<Map<String, Object>>> facets() {
        return ResponseEntity.ok(service.facets());
    }

    /** Push this audience OUT to an ad/social platform as a Custom Audience —
     * seed (lookalike source) or suppress (paid-spend exclusion). */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(activation.activate(id, body));
    }
}
