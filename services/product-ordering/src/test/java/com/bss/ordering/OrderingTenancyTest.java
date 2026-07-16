package com.bss.ordering;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: orders belong to the tenant of the verified token
 * issuer. Cross-tenant access must behave as if the order does not exist
 * (404, never 403), lists must never leak foreign rows, and the tenant
 * predicate composes with the existing party (owner) scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderingTenancyTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.bss.ordering.client.PartyClient partyClient;

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private String createOrderAs(RequestPostProcessor token, String description) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"" + description
                                + "\", \"productOrderItem\": [{\"id\": \"1\", \"action\": \"add\"}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersOrders() throws Exception {
        String idA = createOrderAs(staffOf(ISSUER_A), "tenant-a order");

        // The owning tenant reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("tenant-a order"));
        mockMvc.perform(get(BASE + "/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's order");
        }
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = createOrderAs(staffOf(ISSUER_A), "tenant-a patch target");

        mockMvc.perform(patch(BASE + "/" + idA).with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Still intact and untouched for its owner.
        mockMvc.perform(get(BASE + "/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(jsonPath("$.description").value("tenant-a patch target"));
    }

    @Test
    void tenantScopingComposesWithPartyScoping() throws Exception {
        // The same customer subject in two tenants is two different people.
        String idA = createOrderAs(customerOf(ISSUER_A, "cust-shared"), "tenant-a customer order");

        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-shared")))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-shared")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "?limit=100").with(customerOf(ISSUER_B, "cust-shared")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + idA + "')]").isEmpty());

        // Even tenant-b staff (unscoped party-wise) cannot see it.
        mockMvc.perform(get(BASE + "/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());
    }
}
