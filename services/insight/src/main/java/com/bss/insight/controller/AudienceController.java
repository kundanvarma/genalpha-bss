package com.bss.insight.controller;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.dto.ActivationJobView;
import com.bss.insight.dto.ActivationRequest;
import com.bss.insight.dto.ActivationResult;
import com.bss.insight.dto.AudienceRefreshReceipt;
import com.bss.insight.dto.AudienceRequest;
import com.bss.insight.dto.AudienceView;
import com.bss.insight.dto.Facet;
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
    public ResponseEntity<AudienceView> create(@RequestBody AudienceRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(dto));
    }

    @GetMapping
    public ResponseEntity<List<AudienceView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AudienceView> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AudienceView> patch(@PathVariable String id, @RequestBody AudienceRequest patch) {
        return ResponseEntity.ok(service.patch(id, patch));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(@PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean explain,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean snapshot,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        if (Boolean.TRUE.equals(snapshot)) {
            return ResponseEntity.ok(service.snapshotMembers(id)); // frozen set, instant
        }
        return Boolean.TRUE.equals(explain)
                ? ResponseEntity.ok(service.membersExplain(id))
                : ResponseEntity.ok(service.members(id, limit));
    }

    /** Materialize the audience — freeze its members into a snapshot. */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<AudienceRefreshReceipt> refresh(@PathVariable String id) {
        return ResponseEntity.ok(service.refresh(id));
    }

    /** The BSS traits this tenant holds — real key/value choices for a builder. */
    @GetMapping("/facets")
    public ResponseEntity<List<Facet>> facets() {
        return ResponseEntity.ok(service.facets());
    }

    /** Push this audience OUT to an ad/social platform as a Custom Audience —
     * seed (lookalike source) or suppress (paid-spend exclusion). Async: returns
     * a job; the export runs in the background. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<ActivationResult> activate(@PathVariable String id, @RequestBody ActivationRequest body) {
        return ResponseEntity.accepted().body(activation.activate(id, body));
    }

    /** Poll an activation job: queued -> running -> done|error, with counts. */
    @GetMapping("/activation/{jobId}")
    public ResponseEntity<ActivationJobView> activationJob(@PathVariable String jobId) {
        return ResponseEntity.ok(activation.jobStatus(jobId));
    }

    /** The ad destinations an audience can activate to, and whether configured. */
    @GetMapping("/destinations")
    public ResponseEntity<Map<String, Boolean>> destinations() {
        return ResponseEntity.ok(activation.availableDestinations());
    }
}
