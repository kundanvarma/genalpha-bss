package com.bss.ordering.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * TMF696 at order time: fetch a productOrderRiskAssessment for the party
 * placing THIS order, so the score can ride into the policy context and
 * the OPERATOR's rules decide the threshold — enforcement stays data.
 * Machine identity (bss-ordering holds risk:assess); fail OPEN like the
 * rest of the policy seam: an engine outage must not block commerce, and
 * a rule referencing an absent riskScore simply does not fire.
 */
@Component
public class RiskClient {

    private static final Logger log = LoggerFactory.getLogger(RiskClient.class);

    /** The assessment, reduced to the two context vars the rules see. */
    public record Assessment(int score, String level, String assessmentId) {
    }

    private final RestClient restClient;

    public RiskClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.risk-base-url}") String baseUrl) {
        this.restClient = builder.clone().baseUrl(baseUrl)
                .requestInterceptor(tokenInterceptor).build();
    }

    @SuppressWarnings("unchecked")
    public Assessment assessOrder(String partyId, long totalQuantity, int lineCount,
            boolean verifiedIdentity) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/tmf-api/riskManagement/v4/productOrderRiskAssessment")
                    .body(Map.of(
                            "relatedParty", List.of(Map.of("id", partyId, "role", "customer")),
                            "totalQuantity", totalQuantity,
                            "lineCount", lineCount,
                            "verifiedIdentity", verifiedIdentity))
                    .retrieve()
                    .body(Map.class);
            if (body == null || !(body.get("riskAssessmentResult") instanceof Map<?, ?> result)) {
                return null;
            }
            return new Assessment(
                    Integer.parseInt(String.valueOf(result.get("overallScore"))),
                    String.valueOf(result.get("riskLevel")),
                    String.valueOf(body.get("id")));
        } catch (RestClientException | NumberFormatException e) {
            log.warn("risk engine unreachable, order proceeds unscored (fail-open): {}",
                    e.getMessage());
            return null;
        }
    }
}
