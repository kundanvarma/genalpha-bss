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
    private final RestClient usageClient;
    private final ObjectMapper objectMapper;

    public BssApiClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8098}") String agreementBaseUrl,
            @Value("${bss.downstream.usage-base-url:http://localhost:8097}") String usageBaseUrl) {
        this.agreementClient = builder.baseUrl(agreementBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.usageClient = builder.baseUrl(usageBaseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
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

    private List<Map<String, Object>> parse(String body) {
        try {
            return objectMapper.readValue(body, JSON_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
