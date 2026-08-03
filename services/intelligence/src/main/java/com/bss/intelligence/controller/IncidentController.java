package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.incident.IncidentAgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Episodic memory's face: the traces, and the MANDATORY human verdict. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class IncidentController {

    private final IncidentAgentService agent;

    public IncidentController(IncidentAgentService agent) {
        this.agent = agent;
    }

    @GetMapping("/incident")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(agent.list());
    }

    @PostMapping("/incident/{id}/verdict")
    public ResponseEntity<Map<String, Object>> verdict(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(agent.verdict(id, dto));
    }
}
