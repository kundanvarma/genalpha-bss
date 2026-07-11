package com.bss.assurance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** What healing needs: find affected services, re-home them, document it. */
@Component
public class SelfHealClients {

    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };

    private final RestClient som;
    private final RestClient ticket;
    private final ObjectMapper objectMapper;

    public SelfHealClients(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.som-base-url:http://localhost:8104}") String somBaseUrl,
            @Value("${bss.downstream.ticket-base-url:http://localhost:8092}") String ticketBaseUrl) {
        this.som = builder.baseUrl(somBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.ticket = builder.baseUrl(ticketBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> servicesOnPath(String deliveryPath) {
        try {
            return objectMapper.readValue(som.get()
                    .uri("/tmf-api/serviceInventory/v4/service?deliveryPath=" + deliveryPath)
                    .retrieve().body(String.class), LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    public void migrate(String serviceId, String deliveryPoint) {
        som.post().uri("/tmf-api/serviceInventory/v4/service/" + serviceId + "/migrate")
                .header("Content-Type", "application/json")
                .body(Map.of("deliveryPoint", deliveryPoint))
                .retrieve().toBodilessEntity();
    }

    public void openTicket(String name, String description, String partyId) {
        Map<String, Object> body = partyId == null
                ? Map.of("name", name, "description", description, "severity", "major")
                : Map.of("name", name, "description", description, "severity", "major",
                        "relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        ticket.post().uri("/tmf-api/troubleTicket/v4/troubleTicket")
                .header("Content-Type", "application/json")
                .body(body).retrieve().toBodilessEntity();
    }
}
