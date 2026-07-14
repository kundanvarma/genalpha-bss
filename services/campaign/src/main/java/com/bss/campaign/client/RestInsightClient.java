package com.bss.campaign.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class RestInsightClient implements InsightClient {

    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestInsightClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.insight-base-url:http://localhost:8119}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    /** Fails CLOSED: an unreachable insight component means an empty
     * segment — a blast must never guess its audience. */
    @Override
    public List<Map<String, Object>> segmentMembers(String segment) {
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path("/insight/v1/segmentMembers")
                            .queryParam("segment", segment).build())
                    .retrieve().body(String.class);
            return body == null ? List.of() : objectMapper.readValue(body, JSON_LIST);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }
}
