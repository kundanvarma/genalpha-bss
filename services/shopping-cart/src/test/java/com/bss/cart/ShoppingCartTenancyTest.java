package com.bss.cart;

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
 * Pool-model tenancy: carts belong to the tenant of the verified token issuer;
 * anonymous guest carts belong to the default tenant. Cross-tenant access must
 * behave as if the cart does not exist (404, never 403), lists must never leak
 * foreign rows, and the guest/claim-on-login flow keeps working inside the
 * default tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShoppingCartTenancyTest {

    private static final String BASE = "/tmf-api/shoppingCart/v4/shoppingCart";
    private static final String ISSUER_DEFAULT = "https://idp.genalpha.test/realms/bss";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub))
                .authorities(new SimpleGrantedAuthority("customer"));
    }

    private static RequestPostProcessor agentOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer).subject("agent-1").claim("org", "genalpha-retail"))
                .authorities(new SimpleGrantedAuthority("agent"));
    }

    private String createCartAs(RequestPostProcessor token) throws Exception {
        MvcResult result = mockMvc.perform(token == null
                        ? post(BASE).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cartItem\": [{\"id\": \"line-1\", \"quantity\": 1}]}")
                        : post(BASE).with(token).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cartItem\": [{\"id\": \"line-1\", \"quantity\": 1}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersCarts() throws Exception {
        String idA = createCartAs(customerOf(ISSUER_A, "cust-shared"));

        // The owner reads it back; the other tenant gets 404, not 403 —
        // even for the same subject and even for tenant-b's agent.
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_A, "cust-shared")))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-shared")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + idA).with(agentOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Writes are confined too: tenant-b cannot touch (or claim) it.
        mockMvc.perform(patch(BASE + "/" + idA).with(customerOf(ISSUER_B, "cust-shared"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        mockMvc.perform(get(BASE + "?limit=100").with(customerOf(ISSUER_B, "cust-shared")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + idA + "')]").isEmpty());
        mockMvc.perform(get(BASE + "?limit=100&relatedPartyId=cust-shared").with(agentOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void guestCartsBelongToTheDefaultTenant_andClaimStaysInTenant() throws Exception {
        String id = createCartAs(null);

        // Anonymous access by id keeps working (the id is the secret) —
        // but only inside the default tenant: foreign tenants see nothing.
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id).with(customerOf(ISSUER_A, "cust-a1")))
                .andExpect(status().isNotFound());

        // Claim-on-login: a default-tenant customer claims the guest cart.
        mockMvc.perform(patch(BASE + "/" + id).with(customerOf(ISSUER_DEFAULT, "cust-d1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedParty[0].id").value("cust-d1"));

        // Claimed: the owner keeps access, foreign tenants still get 404.
        mockMvc.perform(get(BASE + "/" + id).with(customerOf(ISSUER_DEFAULT, "cust-d1")))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id).with(customerOf(ISSUER_A, "cust-d1")))
                .andExpect(status().isNotFound());
    }
}
