package com.bss.ticket;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: rows belong to the tenant of the verified token issuer.
 * Cross-tenant access must behave as if the resource does not exist (404,
 * never 403), lists must never leak foreign rows, and the existing party and
 * org scopes stay inner predicates under the tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TroubleTicketTenancyTest {

    private static final String BASE = "/tmf-api/troubleTicket/v4/troubleTicket";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("ticket:read"),
                new SimpleGrantedAuthority("ticket:write"));
    }

    private static RequestPostProcessor agentOf(String issuer, String sub, String org) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub).claim("org", org)).authorities(
                new SimpleGrantedAuthority("agent"),
                new SimpleGrantedAuthority("ticket:read"),
                new SimpleGrantedAuthority("ticket:write"));
    }

    private static RequestPostProcessor backOfficeOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("ticket:read"),
                new SimpleGrantedAuthority("ticket:write"));
    }

    private String customerTicket(String issuer, String sub, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(customerOf(issuer, sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersTickets() throws Exception {
        String idA = customerTicket(ISSUER_A, "cust-ten-1", "Tenant-A outage");

        // The owner reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-ten-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tenant-A outage"));
        mockMvc.perform(get(BASE + "/" + idA).with(backOfficeOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // The SAME subject under another tenant's issuer sees nothing either:
        // tenant is an outer predicate on top of party scoping.
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-ten-1")))
                .andExpect(status().isNotFound());

        // Even an agent of the SAME org name in another tenant sees nothing:
        // tenant is an outer predicate on top of org scoping.
        mockMvc.perform(get(BASE + "/" + idA).with(agentOf(ISSUER_B, "agent-bea", "genalpha-retail")))
                .andExpect(status().isNotFound());

        // Tenant-B lists (back-office, otherwise unscoped) never leak the row.
        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(backOfficeOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's ticket");
        }
        mockMvc.perform(get(BASE + "?limit=100").with(agentOf(ISSUER_B, "agent-bea", "genalpha-retail")))
                .andExpect(jsonPath("$[?(@.id=='" + idA + "')]").isEmpty());
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = customerTicket(ISSUER_A, "cust-ten-2", "Tenant-A patch target");

        mockMvc.perform(patch(BASE + "/" + idA).with(backOfficeOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"inProgress\"}"))
                .andExpect(status().isNotFound());

        // Still intact and workable inside its own tenant.
        mockMvc.perform(get(BASE + "/" + idA).with(agentOf(ISSUER_A, "agent-anna", "genalpha-retail")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("acknowledged"));
    }
}
