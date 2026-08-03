package com.bss.catalog.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * The configurator's window into policy: the SAME block rules that will
 * refuse the order at submit time are consulted at configure time (machine
 * identity — /evaluate is a machine-only seam), and the public deal engine
 * prices the configuration indicatively (anonymous by design). Both calls
 * fail OPEN: a policy outage must never block browsing — the order pipeline
 * remains the enforcing gate.
 */
@Component
public class PolicyClient {

    private static final Logger log = LoggerFactory.getLogger(PolicyClient.class);
    private static final String BASE = "/tmf-api/policyManagement/v4";

    /** The evaluate verdict, reduced to what the configurator needs. */
    public record Verdict(boolean allowed, String message, String ruleName) {
        public static Verdict allow() {
            return new Verdict(true, null, null);
        }
    }

    private final RestClient evaluateClient;
    private final RestClient anonymousClient;

    public PolicyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.policy-base-url}") String baseUrl) {
        this.evaluateClient = builder.clone().baseUrl(baseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.anonymousClient = builder.clone().baseUrl(baseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public Verdict evaluate(Map<String, Object> context) {
        try {
            Map<String, Object> body = evaluateClient.post()
                    .uri(BASE + "/evaluate")
                    .body(Map.of("domain", "order", "context", context))
                    .retrieve()
                    .body(Map.class);
            if (body != null && "deny".equals(body.get("decision"))) {
                String message = body.get("message") == null
                        ? "This configuration is not permitted by a business rule."
                        : String.valueOf(body.get("message"));
                return new Verdict(false, message, String.valueOf(body.get("ruleName")));
            }
            return Verdict.allow();
        } catch (RestClientException e) {
            log.warn("policy service unreachable, allowing configuration (fail-open): {}", e.getMessage());
            return Verdict.allow();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> indicativePrice(Map<String, Object> context) {
        try {
            return anonymousClient.post()
                    .uri(BASE + "/price/indicative")
                    .body(Map.of("context", context))
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("policy service unreachable, skipping indicative pricing (fail-open): {}", e.getMessage());
            return null;
        }
    }
}
