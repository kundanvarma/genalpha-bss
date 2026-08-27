package com.bss.basemigration.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TMF632 view of individuals: birth dates for the age-threshold trigger,
 * region for segment excludes. Machine call under this service's own
 * identity (party:read).
 */
@Component
public class PartyClient {

    private static final int PAGE = 100;
    private static final int MAX_PAGES = 100;

    private final RestClient rest;

    public PartyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.party-base-url:http://localhost:8084}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Every individual in the acting tenant, paged to completion. */
    public List<Map<String, Object>> listIndividuals() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> batch = rest.get()
                    .uri("/tmf-api/party/v4/individual?offset={o}&limit={l}", page * PAGE, PAGE)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < PAGE) {
                break;
            }
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getIndividual(String partyId) {
        try {
            return Optional.ofNullable(rest.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .retrieve()
                    .body(Map.class));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
