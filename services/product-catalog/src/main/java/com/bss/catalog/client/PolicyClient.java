package com.bss.catalog.client;

import com.bss.catalog.dto.IndicativePrice;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
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

    /** The policy service's verdict as it answers: allow|deny and, when a rule spoke, which one and what it said. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(String decision, String ruleId, String ruleName, String message) {
    }

    /** The order-domain context the block rules and the deal engine read: the lines, their count, the largest quantity, the channel. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"items", "itemCount", "maxLineQuantity", "channel", "subtotal"})
    public record OrderContext(List<OrderItem> items, int itemCount, int maxLineQuantity, String channel, BigDecimal subtotal) {

        public OrderContext withSubtotal(BigDecimal subtotal) {
            return new OrderContext(items, itemCount, maxLineQuantity, channel, subtotal);
        }
    }

    @JsonPropertyOrder({"offeringId", "name", "quantity"})
    public record OrderItem(String offeringId, String name, int quantity) {
    }

    private final RestClient evaluateClient;
    private final RestClient anonymousClient;

    public PolicyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.policy-base-url}") String baseUrl) {
        this.evaluateClient = builder.clone().baseUrl(baseUrl)
                .requestInterceptor(tokenInterceptor).build();
        this.anonymousClient = builder.clone().baseUrl(baseUrl).build();
    }

    /** The verdict for any domain — null when the policy service cannot answer,
     *  which launch governance reads as "no envelope matched" (fail-closed). */
    public Decision decision(String domain, Object context) {
        try {
            return evaluateClient.post()
                    .uri(BASE + "/evaluate")
                    .body(Map.of("domain", domain, "context", context))
                    .retrieve()
                    .body(Decision.class);
        } catch (RestClientException e) {
            log.warn("policy service unreachable for domain '{}': {}", domain, e.getMessage());
            return null;
        }
    }

    public Verdict evaluate(OrderContext context) {
        try {
            Decision body = evaluateClient.post()
                    .uri(BASE + "/evaluate")
                    .body(Map.of("domain", "order", "context", context))
                    .retrieve()
                    .body(Decision.class);
            if (body != null && "deny".equals(body.decision())) {
                String message = body.message() == null
                        ? "This configuration is not permitted by a business rule."
                        : body.message();
                return new Verdict(false, message, String.valueOf(body.ruleName()));
            }
            return Verdict.allow();
        } catch (RestClientException e) {
            log.warn("policy service unreachable, allowing configuration (fail-open): {}", e.getMessage());
            return Verdict.allow();
        }
    }

    public IndicativePrice indicativePrice(OrderContext context) {
        try {
            return anonymousClient.post()
                    .uri(BASE + "/price/indicative")
                    .body(Map.of("context", context))
                    .retrieve()
                    .body(IndicativePrice.class);
        } catch (RestClientException e) {
            log.warn("policy service unreachable, skipping indicative pricing (fail-open): {}", e.getMessage());
            return null;
        }
    }
}
