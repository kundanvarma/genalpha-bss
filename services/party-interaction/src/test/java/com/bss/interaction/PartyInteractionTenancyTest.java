package com.bss.interaction;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: interactions belong to the tenant of the verified token
 * issuer. Cross-tenant access must behave as if the interaction does not exist
 * (404, never 403), lists must never leak foreign rows, and the tenant
 * predicate composes with the existing party and org scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartyInteractionTenancyTest {

    private static final String BASE = "/tmf-api/partyInteraction/v4/partyInteraction";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor agentOf(String issuer, String sub, String org) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub).claim("org", org)).authorities(
                new SimpleGrantedAuthority("agent"),
                new SimpleGrantedAuthority("interaction:read"),
                new SimpleGrantedAuthority("interaction:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("interaction:read"));
    }

    private String logAs(RequestPostProcessor token, String customerId, String description) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "%s", "channel": "phone", "direction": "inbound",
                                 "relatedParty": [{"id": "%s", "role": "customer"}]}
                                """.formatted(description, customerId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersInteractions() throws Exception {
        String idA = logAs(agentOf(ISSUER_A, "agent-anna", "genalpha-retail"), "cust-t1", "tenant-a contact");

        // Same org claim, other tenant: 404, not 403 — the row does not exist for them.
        mockMvc.perform(get(BASE + "/" + idA).with(agentOf(ISSUER_A, "agent-anna", "genalpha-retail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("tenant-a contact"));
        mockMvc.perform(get(BASE + "/" + idA).with(agentOf(ISSUER_B, "agent-bert", "genalpha-retail")))
                .andExpect(status().isNotFound());

        // Lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100")
                        .with(agentOf(ISSUER_B, "agent-bert", "genalpha-retail")))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's interaction");
        }
        mockMvc.perform(get(BASE + "?limit=100&relatedPartyId=cust-t1")
                        .with(agentOf(ISSUER_B, "agent-bert", "genalpha-retail")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tenantScopingComposesWithPartyScoping() throws Exception {
        // The same customer subject in two tenants is two different people.
        String idA = logAs(agentOf(ISSUER_A, "agent-anna", "genalpha-retail"), "cust-shared", "about cust-shared");

        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-shared")))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-shared")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "?limit=100").with(customerOf(ISSUER_B, "cust-shared")))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
