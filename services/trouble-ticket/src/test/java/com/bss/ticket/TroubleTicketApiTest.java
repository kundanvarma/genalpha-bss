package com.bss.ticket;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TroubleTicketApiTest {

    private static final String BASE = "/tmf-api/troubleTicket/v4/troubleTicket";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("ticket:read"),
                new SimpleGrantedAuthority("ticket:write"));
    }

    private static RequestPostProcessor agent(String sub, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org", org)).authorities(
                new SimpleGrantedAuthority("agent"),
                new SimpleGrantedAuthority("ticket:read"),
                new SimpleGrantedAuthority("ticket:write"));
    }

    private String customerTicket(String sub, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(customer(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "no internet",
                                 "severity": "major",
                                 "note": [{"text": "router blinking red"}]}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("acknowledged"))
                .andExpect(jsonPath("$.organization.id").value("genalpha-retail"))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void customerTicket_isWorkedByOperatorAgents_notByPartnerAgents() throws Exception {
        String id = customerTicket("cust-t1", "No internet");

        // Operator agent sees and works it.
        mockMvc.perform(get(BASE + "/" + id).with(agent("agent-anna", "genalpha-retail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note[0].text").value("router blinking red"));
        mockMvc.perform(patch(BASE + "/" + id).with(agent("agent-anna", "genalpha-retail"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "inProgress", "note": [{"text": "line test scheduled"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inProgress"))
                .andExpect(jsonPath("$.note.length()").value(2));

        // Partner agent (other org) cannot even see it.
        mockMvc.perform(get(BASE + "/" + id).with(agent("partner-paul", "partner-north")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "?limit=100").with(agent("partner-paul", "partner-north")))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
    }

    @Test
    void customersFollowTheirOwnTickets_andMayOnlyCloseResolved() throws Exception {
        String id = customerTicket("cust-t2", "Slow speeds");

        // Foreign customers see nothing.
        mockMvc.perform(get(BASE + "/" + id).with(customer("cust-nosy")))
                .andExpect(status().isNotFound());

        // Customer cannot drive the workflow...
        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-t2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"inProgress\"}"))
                .andExpect(status().isBadRequest());

        // ...but can close after the agent resolves.
        mockMvc.perform(patch(BASE + "/" + id).with(agent("agent-anna", "genalpha-retail"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"resolved\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-t2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"closed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"));

        // Closed is terminal.
        mockMvc.perform(patch(BASE + "/" + id).with(agent("agent-anna", "genalpha-retail"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"inProgress\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void agentRaisedTicket_belongsToTheNamedCustomer_andAgentOrg() throws Exception {
        mockMvc.perform(post(BASE).with(agent("partner-paul", "partner-north"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Router replacement",
                                 "relatedParty": [{"id": "cust-t3", "role": "customer"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organization.id").value("partner-north"))
                .andExpect(jsonPath("$.relatedParty[0].id").value("cust-t3"));

        // The customer sees it even though a partner org owns the work.
        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-t3")))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItem("Router replacement")));
    }
}
