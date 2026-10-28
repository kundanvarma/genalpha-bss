package com.bss.ordering.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Billing's collections ladder, read with ordering's own machine identity
 * (billing:read). A household is in collections while its case is reminded,
 * warned, restricted or suspended AND no payment plan is agreed — a kept
 * promise to pay is "arranged", not overdue. Current, terminated and written-off
 * cases are not in collections.
 */
@Component
public class RestCollectionsClient implements CollectionsClient {

    private static final Logger log = LoggerFactory.getLogger(RestCollectionsClient.class);
    private static final Set<String> OPEN = Set.of("reminded", "warned", "restricted", "suspended");
    private static final ParameterizedTypeReference<List<Map<String, Object>>> CASES = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public RestCollectionsClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.billing-base-url:http://localhost:8088}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public Optional<Boolean> inCollections(String partyId) {
        try {
            List<Map<String, Object>> cases = restClient.get()
                    .uri("/tmf-api/customerBillManagement/v4/collectionCase?accountId={id}", partyId)
                    .retrieve()
                    .body(CASES);
            if (cases == null) {
                return Optional.empty();
            }
            return Optional.of(cases.stream().anyMatch(RestCollectionsClient::open));
        } catch (RestClientException e) {
            log.warn("collections unreadable for an eligibility check; the fact is left out: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean open(Map<String, Object> c) {
        if (!(c.get("state") instanceof String state) || !OPEN.contains(state)) {
            return false;
        }
        return !(c.get("holds") instanceof Map<?, ?> holds && holds.get("promiseToPay") != null);
    }
}
