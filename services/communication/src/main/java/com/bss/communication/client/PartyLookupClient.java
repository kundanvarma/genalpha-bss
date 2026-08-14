package com.bss.communication.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads a party's display fields so templates can greet a customer by name and,
 * for B2B, name their company. Best-effort: a lookup failure means the token
 * renders empty, never a broken send.
 *
 * <p>B2B distinguishes the ACCOUNT (an Organization — the contract holder) from
 * the CONTACT (an Individual — the human who reads the message). Two seams:
 * {@link #nameTokens} resolves both person tokens ({{party.firstName}}) and org
 * tokens ({{organization.name}}); {@link #recipientsOf} expands an org account
 * to the individuals to actually notify.
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

    /**
     * @return name/org tokens for a party. For an Individual: party.firstName,
     *     party.lastName, and — when the person belongs to a company —
     *     organization.name/tradingName. For an Organization party: just the
     *     organization tokens (a company has no first name). Empty on failure.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nameTokens(String tenantId, String partyId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            Map<String, Object> person = partyClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                    .retrieve().body(Map.class);
            if (person != null) {
                if (person.get("givenName") != null) out.put("party.firstName", person.get("givenName"));
                if (person.get("familyName") != null) out.put("party.lastName", person.get("familyName"));
                if (person.get("organization") instanceof Map<?, ?> org && org.get("id") != null) {
                    out.putAll(orgTokens(tenantId, String.valueOf(org.get("id"))));
                }
                return out;
            }
        } catch (Exception e) {
            // fall through: the party may be an Organization, not an Individual
        }
        out.putAll(orgTokens(tenantId, partyId));
        return out;
    }

    /** organization.name / organization.tradingName for an org party. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> orgTokens(String tenantId, String orgId) {
        try {
            Map<String, Object> org = partyClient.get()
                    .uri("/tmf-api/party/v4/organization/{id}", orgId)
                    .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                    .retrieve().body(Map.class);
            if (org == null) return Map.of();
            Map<String, Object> t = new java.util.LinkedHashMap<>();
            if (org.get("name") != null) t.put("organization.name", org.get("name"));
            if (org.get("tradingName") != null) t.put("organization.tradingName", org.get("tradingName"));
            return t;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Who to actually deliver to. An Individual is just themselves. An
     * Organization ACCOUNT fans out to its member Individuals (the humans who
     * read mail) — so "notify the account" reaches a person, not a legal entity.
     * Falls back to the party itself when it is not an org or has no contacts,
     * so a send is never silently dropped.
     */
    @SuppressWarnings("unchecked")
    public List<String> recipientsOf(String tenantId, String partyId) {
        if (partyId == null) return List.of();
        try {
            // Is it an Individual? Then it is its own recipient (the common case).
            Map<String, Object> person = partyClient.get()
                    .uri("/tmf-api/party/v4/individual/{id}", partyId)
                    .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                    .retrieve().body(Map.class);
            if (person != null) return List.of(partyId);
        } catch (Exception e) {
            // not an individual — try the org's contacts below
        }
        try {
            List<Map<String, Object>> contacts = partyClient.get()
                    .uri("/tmf-api/party/v4/individual?organizationId={org}&limit=100", partyId)
                    .header("Authorization", "Bearer " + tokens.tokenFor(tenantId))
                    .retrieve().body(List.class);
            if (contacts != null && !contacts.isEmpty()) {
                List<String> ids = contacts.stream()
                        .map(c -> c.get("id")).filter(java.util.Objects::nonNull)
                        .map(String::valueOf).distinct().toList();
                if (!ids.isEmpty()) return ids;
            }
        } catch (Exception e) {
            // fall through to self
        }
        return List.of(partyId);
    }
}
