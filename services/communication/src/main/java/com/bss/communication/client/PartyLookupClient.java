package com.bss.communication.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Reads a party's display fields (given/family name) so templates can greet
 * a customer by name. Best-effort: a lookup failure means the token renders
 * empty, never a broken send.
 */
@Component
public class PartyLookupClient {

    private final RestClient partyClient;
    private final MachineTokens tokens;

    public PartyLookupClient(RestClient.Builder builder, MachineTokens tokens,
            @Value("${bss.downstream.party-base-url:http://localhost:8081}") String partyBaseUrl) {
        this.partyClient = builder.baseUrl(partyBaseUrl).build();
        this.tokens = tokens;
    }

    /** @return {"party.firstName": ..., "party.lastName": ...}, empty on any failure. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nameTokens(String tenantId, String partyId) {
        try {
            Map<String, Object> person = partyClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                    .retrieve().body(Map.class);
            if (person == null) return Map.of();
            Map<String, Object> tokens = new java.util.LinkedHashMap<>();
            if (person.get("givenName") != null) tokens.put("party.firstName", person.get("givenName"));
            if (person.get("familyName") != null) tokens.put("party.lastName", person.get("familyName"));
            return tokens;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
