package com.bss.intelligence.controller;

import com.bss.intelligence.incident.IncidentAgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TMF724: the incident agent's episodic memory, standards-addressable.
 * A trace pending its verdict is an ACKNOWLEDGED incident; a verdicted
 * one is RESOLVED — the state machine is the verdict discipline itself.
 */
@RestController
@RequestMapping("/tmf-api/incidentManagement/v4")
public class Tmf724Controller {

    private final IncidentAgentService agent;

    public Tmf724Controller(IncidentAgentService agent) {
        this.agent = agent;
    }

    @GetMapping("/incident")
    public ResponseEntity<List<Map<String, Object>>> incidents() {
        return ResponseEntity.ok(agent.list().stream().map(Tmf724Controller::incidentView).toList());
    }

    @GetMapping("/incident/{id}")
    public ResponseEntity<Map<String, Object>> incident(@PathVariable("id") String id) {
        return agent.list().stream().filter(t -> id.equals(t.get("id"))).findFirst()
                .map(t -> ResponseEntity.ok(incidentView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    static Map<String, Object> incidentView(Map<String, Object> trace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", trace.get("id"));
        map.put("href", "/tmf-api/incidentManagement/v4/incident/" + trace.get("id"));
        map.put("name", "Process incident: " + trace.get("signature"));
        map.put("category", "process");
        boolean pending = "pending".equals(trace.get("verdict"));
        map.put("state", pending ? "acknowledged" : "resolved");
        map.put("ackState", pending ? "unacknowledged" : String.valueOf(trace.get("verdict")));
        map.put("occurTime", trace.get("createdAt"));
        map.put("sourceObject", List.of(Map.of("id", trace.get("processFlowId"),
                "@referredType", "ProcessFlow")));
        Map<String, Object> rootCause = new LinkedHashMap<>();
        rootCause.put("hypothesis", trace.get("hypothesis"));
        rootCause.put("confidence", trace.get("confidence"));
        rootCause.put("source", trace.get("source"));
        map.put("rootCauseAid", rootCause);
        if (trace.get("ticketId") != null) {
            map.put("troubleTicket", List.of(Map.of("id", trace.get("ticketId"),
                    "@referredType", "TroubleTicket")));
        }
        map.put("@type", "Incident");
        return map;
    }
}
