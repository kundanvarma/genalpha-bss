package com.bss.party;

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
 * never 403), lists must never leak foreign rows, and party scoping
 * (self-registration, self-access) stays an inner predicate under the tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartyAccountTenancyTest {

    private static final String INDIVIDUAL_BASE = "/tmf-api/party/v4/individual";
    private static final String BILLING_ACCOUNT_BASE = "/tmf-api/accountManagement/v4/billingAccount";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("party:read"),
                new SimpleGrantedAuthority("party:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("party:read"),
                new SimpleGrantedAuthority("party:write"));
    }

    private String createBillingAccount(String issuer, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BILLING_ACCOUNT_BASE).with(staffOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersBillingAccounts() throws Exception {
        String idA = createBillingAccount(ISSUER_A, "Tenant-A only account");

        // The owner reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BILLING_ACCOUNT_BASE + "/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tenant-A only account"));
        mockMvc.perform(get(BILLING_ACCOUNT_BASE + "/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BILLING_ACCOUNT_BASE + "?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's billing account");
        }
        mockMvc.perform(get(BILLING_ACCOUNT_BASE).param("limit", "100")
                        .param("name", "Tenant-A only account").with(staffOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = createBillingAccount(ISSUER_A, "Tenant-A patch target");

        mockMvc.perform(patch(BILLING_ACCOUNT_BASE + "/" + idA).with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BILLING_ACCOUNT_BASE + "/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Still intact and untouched for its owner.
        mockMvc.perform(get(BILLING_ACCOUNT_BASE + "/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(jsonPath("$.name").value("Tenant-A patch target"));
    }

    @Test
    void selfRegisteredIndividualsAreConfinedToTheirTenant() throws Exception {
        // Self-registration by a customer token: the id IS the token subject.
        mockMvc.perform(post(INDIVIDUAL_BASE).with(customerOf(ISSUER_A, "cust-tenant-alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\": \"Alice\", \"familyName\": \"OfTenantA\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cust-tenant-alice"));

        // Self-access keeps working exactly as before inside the tenant.
        mockMvc.perform(get(INDIVIDUAL_BASE + "/cust-tenant-alice")
                        .with(customerOf(ISSUER_A, "cust-tenant-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.givenName").value("Alice"));

        // Tenant-B staff (unscoped by party) cannot see the foreign individual.
        mockMvc.perform(get(INDIVIDUAL_BASE + "/cust-tenant-alice").with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // The SAME subject under tenant-B's issuer sees nothing: tenant is an
        // outer predicate on top of the party-scope subject check.
        mockMvc.perform(get(INDIVIDUAL_BASE + "/cust-tenant-alice")
                        .with(customerOf(ISSUER_B, "cust-tenant-alice")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(INDIVIDUAL_BASE).with(customerOf(ISSUER_B, "cust-tenant-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Tenant-B staff lists never leak the foreign individual.
        MvcResult listB = mockMvc.perform(get(INDIVIDUAL_BASE + "?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains("cust-tenant-alice")) {
            throw new AssertionError("tenant B list leaked tenant A's individual");
        }
    }
}
