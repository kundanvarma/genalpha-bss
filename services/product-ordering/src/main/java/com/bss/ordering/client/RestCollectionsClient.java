package com.bss.ordering.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Billing's collections ladder, read with ordering's own machine identity
 * (billing:read). A household is in collections while its case is reminded,
 * warned, restricted or suspended AND nothing pauses the ladder: a promise to
 * pay, a disputed amount, or hardship each means the household is not simply
 * overdue, so none of them counts (decided with the operator, Nov 2026).
 * Current, terminated and written-off cases are not in collections.
 */
@Component
public class RestCollectionsClient implements CollectionsClient {

    private static final Logger log = LoggerFactory.getLogger(RestCollectionsClient.class);
    private static final Set<String> OPEN = Set.of("reminded", "warned", "restricted", "suspended");
    private static final ParameterizedTypeReference<List<CollectionCase>> CASES = new ParameterizedTypeReference<>() {
    };

    /** Ordering's view of billing's collection case: only what this question needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionCase(String state, Holds holds) {
    }

    /** What pauses the ladder — billing's CollectionCaseView.Holds, read as far as ordering needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holds(Amount promiseToPay, Amount dispute, Boolean hardship) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(BigDecimal amount) {
    }

    private final RestClient restClient;

    public RestCollectionsClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.billing-base-url:http://localhost:8088}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public Optional<Boolean> inCollections(String partyId) {
        try {
            List<CollectionCase> cases = restClient.get()
                    .uri("/tmf-api/customerBillManagement/v4/collectionCase?accountId={id}", partyId)
                    .retrieve()
                    .body(CASES);
            if (cases == null) {
                return Optional.empty();
            }
            return Optional.of(cases.stream().anyMatch(RestCollectionsClient::overdue));
        } catch (RestClientException e) {
            log.warn("collections unreadable for an eligibility check; the fact is left out: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** On the ladder, and nothing — a promise, a dispute, hardship — pauses it. */
    static boolean overdue(CollectionCase c) {
        if (c == null || c.state() == null || !OPEN.contains(c.state())) {
            return false;
        }
        Holds h = c.holds();
        boolean paused = h != null && (h.promiseToPay() != null || h.dispute() != null || Boolean.TRUE.equals(h.hardship()));
        return !paused;
    }
}
