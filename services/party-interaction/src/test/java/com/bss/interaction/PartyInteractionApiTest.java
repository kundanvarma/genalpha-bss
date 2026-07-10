package com.bss.interaction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartyInteractionApiTest {

    private static final String BASE = "/tmf-api/partyInteraction/v4/partyInteraction";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("interaction:read"));
    }

    private static RequestPostProcessor agent(String sub, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org", org)).authorities(
                new SimpleGrantedAuthority("agent"),
                new SimpleGrantedAuthority("interaction:read"),
                new SimpleGrantedAuthority("interaction:write"));
    }

    private void log(String agentSub, String org, String customerId, String description) throws Exception {
        mockMvc.perform(post(BASE).with(agent(agentSub, org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "%s", "channel": "phone", "direction": "inbound",
                                 "relatedParty": [{"id": "%s", "role": "customer"}]}
                                """.formatted(description, customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.organization.id").value(org));
    }

    @Test
    void agentLogsContact_visibleToOrgAndCustomer_notToOtherOrg() throws Exception {
        log("agent-anna", "genalpha-retail", "cust-i1", "Called about slow fiber");
        log("partner-paul", "partner-north", "cust-i1", "Partner shop visit");

        // Operator agent sees only the operator's history for this customer.
        mockMvc.perform(get(BASE + "?relatedPartyId=cust-i1&limit=100")
                        .with(agent("agent-anna", "genalpha-retail")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value("Called about slow fiber"));

        // The customer sees every touchpoint that concerns them, org-agnostic.
        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-i1")))
                .andExpect(jsonPath("$.length()").value(2));

        // Other customers see nothing.
        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-else")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void interactionsRequireACustomer() throws Exception {
        mockMvc.perform(post(BASE).with(agent("agent-anna", "genalpha-retail"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"orphan note\"}"))
                .andExpect(status().isBadRequest());
    }
}
