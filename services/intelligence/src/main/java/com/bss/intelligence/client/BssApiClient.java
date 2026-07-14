package com.bss.intelligence.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Read-only machine access to the BSS APIs the scorer needs, always with
 * the acting tenant's own identity (the interceptor picks the token per
 * tenant). The scorer never writes to any domain — its only output is
 * events on its own topic.
 */
@Component
public class BssApiClient {

    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final RestClient agreementClient;
    private final RestClient recommendationClient;
    private final RestClient insightClient;
    private final RestClient inventoryClient;
    private final RestClient usageClient;
    private final RestClient ticketClient;
    private final RestClient assuranceClient;
    private final ObjectMapper objectMapper;

    public BssApiClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8098}") String agreementBaseUrl,
            @Value("${bss.downstream.usage-base-url:http://localhost:8097}") String usageBaseUrl,
            @Value("${bss.downstream.ticket-base-url:http://localhost:8092}") String ticketBaseUrl,
            @Value("${bss.downstream.assurance-base-url:http://localhost:8105}") String assuranceBaseUrl,
            @Value("${bss.downstream.recommendation-base-url:http://localhost:8102}") String recommendationBaseUrl,
            @Value("${bss.downstream.insight-base-url:http://localhost:8119}") String insightBaseUrl,
            @Value("${bss.downstream.inventory-base-url:http://localhost:8083}") String inventoryBaseUrl) {
        this.agreementClient = builder.baseUrl(agreementBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.usageClient = builder.baseUrl(usageBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.ticketClient = builder.baseUrl(ticketBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.assuranceClient = builder.baseUrl(assuranceBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.recommendationClient = builder.baseUrl(recommendationBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.insightClient = builder.baseUrl(insightBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.inventoryClient = builder.baseUrl(inventoryBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> ticketsOf(String partyId) {
        String body = ticketClient.get()
                .uri("/tmf-api/troubleTicket/v4/troubleTicket?relatedPartyId=" + partyId + "&limit=50")
                .retrieve().body(String.class);
        return parse(body);
    }

    public List<Map<String, Object>> openServiceProblems() {
        String body = assuranceClient.get()
                .uri("/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open")
                .retrieve().body(String.class);
        return parse(body);
    }

    public List<Map<String, Object>> activeAgreements() {
        String body = agreementClient.get()
                .uri("/tmf-api/agreementManagement/v4/agreement?status=active&limit=100")
                .retrieve().body(String.class);
        return parse(body);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> usageMeters(String partyId) {
        String body = usageClient.get()
                .uri("/tmf-api/usageConsumption/v4/queryUsageConsumption?relatedPartyId=" + partyId)
                .retrieve().body(String.class);
        try {
            Map<String, Object> report = objectMapper.readValue(body, Map.class);
            return report.get("bucket") instanceof List<?> buckets
                    ? (List<Map<String, Object>>) buckets : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The TMF680 ranking (already interest-fused) — NBO reasons over it. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recommendationItems(String partyId) {
        try {
            String body = recommendationClient.get()
                    .uri("/tmf-api/recommendationManagement/v4/recommendation?relatedPartyId=" + partyId)
                    .retrieve().body(String.class);
            List<Map<String, Object>> recs = parse(body);
            return recs.isEmpty() ? List.of()
                    : recs.get(0).get("recommendationItem") instanceof List<?> items
                            ? (List<Map<String, Object>>) items : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> interestsOf(String partyId) {
        try {
            Map<String, Object> profile = objectMapper.readValue(insightClient.get()
                    .uri("/insight/v1/partyProfile?partyId=" + partyId)
                    .retrieve().body(String.class), Map.class);
            return profile.get("interests") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> holdingsOf(String partyId) {
        try {
            String body = inventoryClient.get()
                    .uri("/tmf-api/productInventory/v4/product?relatedPartyId=" + partyId + "&limit=50")
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parse(String body) {
        try {
            return objectMapper.readValue(body, JSON_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
