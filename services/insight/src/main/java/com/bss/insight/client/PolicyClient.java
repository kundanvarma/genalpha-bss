package com.bss.insight.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;

/**
 * The decisioning seam: experience rules authored as DATA in the policy
 * component (domain 'personalization'). Fails OPEN to the coded default —
 * an unreachable rules engine must not blank the shop window.
 */
@Component
public class PolicyClient {

    private final RestClient restClient;

    public PolicyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.policy-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> experience(Map<String, Object> context) {
        try {
            Map<String, Object> decision = restClient.post()
                    .uri("/tmf-api/policyManagement/v4/personalization/experience")
                    .body(Map.of("context", context))
                    .retrieve().body(Map.class);
            return decision == null || decision.isEmpty()
                    ? Optional.empty() : Optional.of(decision);
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }
}
