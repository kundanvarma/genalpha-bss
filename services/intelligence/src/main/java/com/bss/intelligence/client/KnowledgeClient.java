package com.bss.intelligence.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Knowledge search ON BEHALF OF the asking user: the caller's own bearer
 * rides through, so the library's audience filter applies to the ANSWER
 * exactly as it would to a manual search — a customer's question can never
 * be answered from the CSR shelf.
 */
@Component
public class KnowledgeClient {

    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public KnowledgeClient(RestClient.Builder builder, ObjectMapper objectMapper,
            @Value("${bss.downstream.knowledge-base-url:http://localhost:8118}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> searchAs(String bearerToken, String q) {
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path("/tmf-api/knowledgeManagement/v4/article")
                            .queryParam("q", q).build())
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve().body(String.class);
            return body == null ? List.of() : objectMapper.readValue(body, JSON_LIST);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }
}
