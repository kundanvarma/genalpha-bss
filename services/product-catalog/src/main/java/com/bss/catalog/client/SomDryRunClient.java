package com.bss.catalog.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Asks the orchestrator what would happen when someone orders an offering
 * (CONTEXT.md: dry run), under the CALLER's token — the person looking at the
 * launch decision is the one entitled to the answer, and the catalog's own
 * machine identity holds no orchestrator authority. Fails soft: an
 * unreachable orchestrator, a missing token or a refusal all answer
 * {@code null}, which launch governance reads as "could not be verified",
 * never as a block.
 */
@Component
public class SomDryRunClient {

    private static final Logger log = LoggerFactory.getLogger(SomDryRunClient.class);

    /** The readiness item's owner (a system, not a role) and its label. */
    public static final String OWNER = "service-orchestration";
    public static final String LABEL = "Fulfilment plan";

    /** The slice of the plan launch governance reads; the offering page reads the whole plan itself. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Plan(String verdict, String reason, List<String> summary, boolean fallback) {
    }

    private final RestClient client;

    public SomDryRunClient(RestClient.Builder builder,
            @Value("${bss.downstream.som-base-url:http://localhost:8104}") String baseUrl) {
        this.client = builder.clone().baseUrl(baseUrl).build();
    }

    /** The plan, or null when it could not be obtained (no caller token, unreachable, refused). */
    public Plan plan(String offeringId) {
        String bearer = callerToken();
        if (bearer == null) {
            return null;
        }
        try {
            return client.post()
                    .uri("/som/v1/fulfilment/dryRun")
                    .header("Authorization", "Bearer " + bearer)
                    .body(Map.of("offeringId", offeringId))
                    .retrieve()
                    .body(Plan.class);
        } catch (RestClientException e) {
            log.warn("fulfilment dry run unavailable for offering {}: {}", offeringId, e.getMessage());
            return null;
        }
    }

    private static String callerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : null;
    }
}
