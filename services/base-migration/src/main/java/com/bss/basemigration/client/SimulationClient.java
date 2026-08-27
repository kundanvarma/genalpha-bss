package com.bss.basemigration.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The rehearsal gate's witness: a plan may only arm with a simulation
 * receipt that actually exists on the pricing simulator's shelf. The
 * simulator lists saved reports; we match the ref against their ids.
 * Machine call under this service's own identity (the simulator's
 * doors are catalog:write).
 */
@Component
public class SimulationClient {

    private final RestClient rest;

    public SimulationClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.intelligence-base-url:http://localhost:8109}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** True when the simulator has a saved report with this id. */
    public boolean simulationExists(String simulationRef) {
        List<Map<String, Object>> reports = rest.get()
                .uri("/ai/v1/simulate/priceChange")
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        if (reports == null) {
            return false;
        }
        return reports.stream().anyMatch(r -> simulationRef.equals(String.valueOf(r.get("id"))));
    }
}
