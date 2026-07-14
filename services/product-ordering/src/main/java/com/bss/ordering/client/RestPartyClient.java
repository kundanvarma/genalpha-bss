package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestPartyClient implements PartyClient {

    private final RestClient restClient;

    public RestPartyClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.party-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public boolean billingAccountExists(String id) {
        try {
            restClient.get()
                    .uri("/tmf-api/accountManagement/v4/billingAccount/{id}", id)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            throw new DownstreamException("party-account is unreachable", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Optional<String> organizationOf(String partyId) {
        try {
            java.util.Map<String, Object> person = restClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .retrieve()
                    .body(java.util.Map.class);
            if (person != null && person.get("organization") instanceof java.util.Map<?, ?> org
                    && org.get("id") != null) {
                return java.util.Optional.of(String.valueOf(org.get("id")));
            }
            return java.util.Optional.empty();
        } catch (HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        } catch (RestClientException e) {
            throw new DownstreamException("party-account is unreachable", e);
        }
    }

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
        } catch (HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        } catch (RestClientException e) {
            throw new DownstreamException("party-account is unreachable", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Optional<String> householdPayerOf(String partyId) {
        try {
            java.util.Map<String, Object> person = restClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .retrieve()
                    .body(java.util.Map.class);
            if (person != null && person.get("householdPayer") instanceof java.util.Map<?, ?> payer
                    && payer.get("id") != null && "active".equals(payer.get("status"))) {
                return java.util.Optional.of(String.valueOf(payer.get("id")));
            }
            return java.util.Optional.empty();
        } catch (HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        } catch (RestClientException e) {
            throw new DownstreamException("party-account is unreachable", e);
        }
    }
}
