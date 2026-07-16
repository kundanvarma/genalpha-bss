package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * The two party facts a TRANSFER needs, live from party management:
 * does the target exist, and which organization does a person belong to
 * (a business admin may only move lines INSIDE their own company).
 * Fail-closed: an unreachable party source refuses the transfer — moving
 * a line to an unverifiable stranger is worse than asking to try later.
 */
@Component
public class PartyOrgClient {

    private static final Logger log = LoggerFactory.getLogger(PartyOrgClient.class);

    private final RestClient restClient;

    public PartyOrgClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.party-base-url:http://localhost:8081}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> individualOf(String partyId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .retrieve().body(Map.class));
        } catch (Exception e) {
            log.warn("party lookup failed for {}: {}", partyId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> orgOf(String partyId) {
        return individualOf(partyId)
                .filter(p -> p.get("organization") instanceof Map<?, ?> org && ((Map<?, ?>) p.get("organization")).get("id") != null)
                .map(p -> String.valueOf(((Map<?, ?>) p.get("organization")).get("id")));
    }
}
