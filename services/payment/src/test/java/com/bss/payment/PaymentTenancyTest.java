package com.bss.payment;

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
 * Pool-model tenancy: payments belong to the tenant of the verified token
 * issuer. Cross-tenant access must behave as if the payment does not exist
 * (404, never 403), and lists must never leak foreign rows. Party scoping
 * remains an additional confinement inside each tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentTenancyTest {

    private static final String BASE = "/tmf-api/paymentManagement/v4/payment";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor machineOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"));
    }

    private String authorize(RequestPostProcessor token) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "one-time charges", "amount": {"unit": "EUR", "value": 49.00},
                                 "paymentMethod": {"@type": "bankCard", "cardNumber": "4242424242424242",
                                  "expiry": "12/28", "cvc": "123"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersRows() throws Exception {
        String idA = authorize(machineOf(ISSUER_A));

        // The owner tenant reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/" + idA).with(machineOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("authorized"));
        mockMvc.perform(get(BASE + "/" + idA).with(machineOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(machineOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's payment");
        }
        mockMvc.perform(get(BASE).param("limit", "100")
                        .param("id", idA).with(machineOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = authorize(machineOf(ISSUER_A));

        mockMvc.perform(patch(BASE + "/" + idA).with(machineOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"captured\"}"))
                .andExpect(status().isNotFound());

        // Still authorized and untouched for its owner tenant.
        mockMvc.perform(get(BASE + "/" + idA).with(machineOf(ISSUER_A)))
                .andExpect(jsonPath("$.status").value("authorized"));
    }

    @Test
    void sameCustomerInAnotherTenantSeesNothing() throws Exception {
        // Same subject in both tenants: party scoping alone would match, the
        // tenant predicate must still hide the foreign row.
        String idA = authorize(customerOf(ISSUER_A, "cust-alice"));

        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedParty[0].id").value("cust-alice"));
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-alice")))
                .andExpect(status().isNotFound());

        MvcResult listB = mockMvc.perform(get(BASE + "?limit=100").with(customerOf(ISSUER_B, "cust-alice")))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("cross-tenant customer list leaked tenant A's payment");
        }
    }

    @Test
    void tokensWithoutAnIssuerFallBackToTheDefaultTenant() throws Exception {
        // Legacy-style token (no iss claim in mocks): acts inside the default
        // tenant — existing single-tenant tests keep passing.
        String id = authorize(jwt().authorities(
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write")));
        mockMvc.perform(get(BASE + "/" + id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("payment:read"))))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id).with(machineOf(ISSUER_A)))
                .andExpect(status().isNotFound());
    }
}
