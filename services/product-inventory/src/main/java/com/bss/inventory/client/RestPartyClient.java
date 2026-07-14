package com.bss.inventory.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestPartyClient implements PartyClient {

    private final RestClient restClient;

    public RestPartyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.party-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Fails CLOSED: this link authorizes a family view, so an unreachable
     * party source means "no household", never a guess. */
    @Override
    @SuppressWarnings("unchecked")
    public java.util.Optional<java.util.Map<String, Object>> householdLinkOf(String partyId) {
        try {
            java.util.Map<String, Object> person = restClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .retrieve()
                    .body(java.util.Map.class);
            if (person != null && person.get("householdPayer") instanceof java.util.Map<?, ?> payer
                    && payer.get("id") != null) {
                return java.util.Optional.of((java.util.Map<String, Object>) payer);
            }
            return java.util.Optional.empty();
        } catch (RestClientException e) {
            return java.util.Optional.empty();
        }
    }
}
