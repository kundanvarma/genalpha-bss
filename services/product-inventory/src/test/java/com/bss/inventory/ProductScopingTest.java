package com.bss.inventory;

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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inventory scoping: products are owned by the customer party found in their
 * relatedParty (order provisioning sends it); a "customer" token sees only its
 * own products.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductScopingTest {

    private static final String BASE = "/tmf-api/productInventory/v4/product";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("inventory:read"));
    }

    private static RequestPostProcessor machine() {
        return jwt().authorities(
                new SimpleGrantedAuthority("inventory:read"),
                new SimpleGrantedAuthority("inventory:write"));
    }

    private String provisionFor(String partyId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "status": "active",
                                 "relatedParty": [{"id": "%s", "role": "customer", "@referredType": "Individual"}]}
                                """.formatted(name, partyId)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void customerSeesOwnProductsOnly() throws Exception {
        String aliceProduct = provisionFor("cust-alice", "alice fiber");
        provisionFor("cust-bob", "bob fiber");

        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(hasItem("alice fiber")))
                .andExpect(jsonPath("$[?(@.name=='bob fiber')]").isEmpty());

        mockMvc.perform(get(BASE + "/" + aliceProduct).with(customer("cust-alice")))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + aliceProduct).with(customer("cust-bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void machineTokenStaysUnscoped() throws Exception {
        String product = provisionFor("cust-carol", "carol tv");
        mockMvc.perform(get(BASE + "/" + product).with(machine()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedParty[*].id").value(hasItem("cust-carol")));
    }
}
