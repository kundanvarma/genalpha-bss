package com.bss.inventory;

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
 * Pool-model tenancy: rows belong to the tenant of the verified token issuer.
 * Cross-tenant access must behave as if the resource does not exist (404,
 * never 403), and lists must never leak foreign rows. Party scoping remains
 * an additional confinement inside each tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryTenancyTest {

    private static final String BASE = "/tmf-api/productInventory/v4/product";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor machineOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("inventory:read"),
                new SimpleGrantedAuthority("inventory:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("inventory:read"));
    }

    private String provisionFor(String issuer, String partyId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(machineOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "status": "active",
                                 "relatedParty": [{"id": "%s", "role": "customer", "@referredType": "Individual"}]}
                                """.formatted(name, partyId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersRows() throws Exception {
        String idA = provisionFor(ISSUER_A, "cust-alice", "tenant-a fiber");

        // The owner tenant reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/" + idA).with(machineOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("tenant-a fiber"));
        mockMvc.perform(get(BASE + "/" + idA).with(machineOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(machineOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's product");
        }
        mockMvc.perform(get(BASE).param("limit", "100")
                        .param("name", "tenant-a fiber").with(machineOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = provisionFor(ISSUER_A, "cust-alice", "tenant-a patch target");

        mockMvc.perform(patch(BASE + "/" + idA).with(machineOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/" + idA).with(machineOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Still intact and untouched for its owner tenant.
        mockMvc.perform(get(BASE + "/" + idA).with(machineOf(ISSUER_A)))
                .andExpect(jsonPath("$.name").value("tenant-a patch target"));
    }

    @Test
    void sameCustomerInAnotherTenantSeesNothing() throws Exception {
        // Same subject in both tenants: party scoping alone would match, the
        // tenant predicate must still hide the foreign row.
        String idA = provisionFor(ISSUER_A, "cust-alice", "alice tenant-a plan");

        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-alice")))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-alice")))
                .andExpect(status().isNotFound());

        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(customerOf(ISSUER_B, "cust-alice")))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("cross-tenant customer list leaked tenant A's product");
        }
    }

    @Test
    void tokensWithoutAnIssuerFallBackToTheDefaultTenant() throws Exception {
        // Legacy-style token (no iss claim in mocks): acts inside the default
        // tenant — existing single-tenant tests keep passing.
        MvcResult result = mockMvc.perform(post(BASE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("inventory:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"no-issuer product\", \"status\": \"active\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get(BASE + "/" + id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("inventory:read"))))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id).with(machineOf(ISSUER_A)))
                .andExpect(status().isNotFound());
    }
}
