package com.bss.ordering.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "bss.policy.enabled", havingValue = "true", matchIfMissing = true)
public class RestPolicyClient implements PolicyClient {

    private static final Logger log = LoggerFactory.getLogger(RestPolicyClient.class);
    private static final String BASE = "/tmf-api/policyManagement/v4";

    private final RestClient restClient;

    public RestPolicyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.policy-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision evaluateOrder(Map<String, Object> context) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri(BASE + "/evaluate")
                    .body(Map.of("domain", "order", "context", context))
                    .retrieve()
                    .body(Map.class);
            if (body != null && "deny".equals(body.get("decision"))) {
                String message = body.get("message") == null
                        ? "This order is not permitted by a business rule."
                        : String.valueOf(body.get("message"));
                return Decision.deny(message, String.valueOf(body.get("ruleName")));
            }
            return Decision.allow();
        } catch (RestClientException e) {
            // Fail open: a policy outage must not block commerce. A real DENY
            // comes back as a 200 body above; only transport/2xx failures land here.
            log.warn("policy service unreachable, allowing order (fail-open): {}", e.getMessage());
            return Decision.allow();
        }
    }
}
