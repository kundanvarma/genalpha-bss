package com.bss.flow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Live Flow — the choreography made visible. An event-driven BSS's value is
 * loose coupling: components react to events they never see the source of.
 * That magic is invisible by nature. This service consumes every business
 * event and streams it to a browser so you can WATCH the system work — a
 * customer orders, the event hits the bus, and communication, the campaign
 * engine and the orchestrator all light up as they react. Read-only, no
 * database, off the critical path: an observability surface, like Grafana.
 */
@SpringBootApplication
public class FlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowApplication.class, args);
    }
}
