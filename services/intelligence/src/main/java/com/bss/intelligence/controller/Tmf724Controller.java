package com.bss.intelligence.controller;

import com.bss.intelligence.incident.IncidentAgentService;
import com.bss.intelligence.incident.IncidentTraceView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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
    public ResponseEntity<List<Incident>> incidents() {
        return ResponseEntity.ok(agent.list().stream().map(Tmf724Controller::incidentView).toList());
    }

    @GetMapping("/incident/{id}")
    public ResponseEntity<Incident> incident(@PathVariable("id") String id) {
        return agent.list().stream().filter(t -> id.equals(t.id())).findFirst()
                .map(t -> ResponseEntity.ok(incidentView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The TMF724 Incident shape over a trace. */
    @JsonPropertyOrder({"id", "href", "name", "category", "state", "ackState", "occurTime", "sourceObject",
            "rootCauseAid", "troubleTicket", "@type"})
    public record Incident(
            String id,
            String href,
            String name,
            String category,
            String state,
            String ackState,
            OffsetDateTime occurTime,
            List<Ref> sourceObject,
            RootCause rootCauseAid,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Ref> troubleTicket,
            @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"id", "@referredType"})
    public record Ref(String id, @JsonProperty("@referredType") String referredType) {
    }

    @JsonPropertyOrder({"hypothesis", "confidence", "source"})
    public record RootCause(String hypothesis, BigDecimal confidence, String source) {
    }

    static Incident incidentView(IncidentTraceView trace) {
        boolean pending = "pending".equals(trace.verdict());
        return new Incident(trace.id(),
                "/tmf-api/incidentManagement/v4/incident/" + trace.id(),
                "Process incident: " + trace.signature(),
                "process",
                pending ? "acknowledged" : "resolved",
                pending ? "unacknowledged" : String.valueOf(trace.verdict()),
                trace.createdAt(),
                List.of(new Ref(trace.processFlowId(), "ProcessFlow")),
                new RootCause(trace.hypothesis(), trace.confidence(), trace.source()),
                trace.ticketId() == null ? null : List.of(new Ref(trace.ticketId(), "TroubleTicket")),
                "Incident");
    }
}
