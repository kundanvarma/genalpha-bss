package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.incident.IncidentAgentService;
import com.bss.intelligence.incident.IncidentRunbookView;
import com.bss.intelligence.incident.IncidentStats;
import com.bss.intelligence.incident.IncidentTraceView;
import com.bss.intelligence.incident.VerdictRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Episodic memory's face: the traces, and the MANDATORY human verdict. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class IncidentController {

    private final IncidentAgentService agent;

    public IncidentController(IncidentAgentService agent) {
        this.agent = agent;
    }

    @GetMapping("/incident")
    public ResponseEntity<List<IncidentTraceView>> list() {
        return ResponseEntity.ok(agent.list());
    }

    @GetMapping("/incident/stats")
    public ResponseEntity<IncidentStats> stats() {
        return ResponseEntity.ok(agent.stats());
    }

    @GetMapping("/runbook")
    public ResponseEntity<List<IncidentRunbookView>> runbooks() {
        return ResponseEntity.ok(agent.listRunbooks());
    }

    @PostMapping("/runbook/{id}/{decision}")
    public ResponseEntity<IncidentRunbookView> decide(@PathVariable("id") String id,
            @PathVariable("decision") String decision,
            @RequestBody(required = false) VerdictRequest dto) {
        return ResponseEntity.ok(agent.decideRunbook(id, decision, dto == null ? null : dto.note()));
    }

    @PostMapping("/incident/{id}/verdict")
    public ResponseEntity<IncidentTraceView> verdict(@PathVariable("id") String id,
            @RequestBody VerdictRequest dto) {
        return ResponseEntity.ok(agent.verdict(id, dto));
    }
}
