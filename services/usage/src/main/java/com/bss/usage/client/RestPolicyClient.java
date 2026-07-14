package com.bss.usage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;

@Component
public class RestPolicyClient implements PolicyClient {

    private final RestClient restClient;

    public RestPolicyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.policy-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Fails OPEN: policy vetoes are extra guardrails on top of the coded
     * defaults and product levers — an unreachable policy service must not
     * take gifting down with it. */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> giftingDeny(Map<String, Object> context) {
        try {
            Map<String, Object> decision = restClient.post()
                    .uri("/tmf-api/policyManagement/v4/evaluate")
                    .body(Map.of("domain", "gifting", "context", context))
                    .retrieve().body(Map.class);
            if (decision != null && "deny".equals(decision.get("decision"))) {
                return Optional.of(String.valueOf(decision.getOrDefault("message",
                        "a business rule does not permit this gift")));
            }
            return Optional.empty();
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }
}
