package com.bss.ordering;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Order scoping: a token with the "customer" role owns every order it places,
 * sees nothing but its own, and may only cancel — never edit or delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderScopingTest {

    // household/payer lookups postdate this test: empty answers = plain
    // self-orders, which is what scoping scenarios are about
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.bss.ordering.client.PartyClient partyClient;

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private String createOrderAs(RequestPostProcessor token, String description) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"" + description + "\", \"productOrderItem\": [{\"id\": \"1\", \"action\": \"add\"}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void customerOrder_carriesOwnerAsRelatedParty() throws Exception {
        mockMvc.perform(post(BASE).with(customer("cust-alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"alice order\", \"productOrderItem\": [{\"id\": \"1\", \"action\": \"add\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relatedParty[*].id").value(hasItem("cust-alice")))
                .andExpect(jsonPath("$.relatedParty[?(@.id=='cust-alice')].role").value(hasItem("customer")));
    }

    @Test
    void customersAreIsolatedFromEachOther() throws Exception {
        String aliceOrder = createOrderAs(customer("cust-alice"), "alice isolated");
        createOrderAs(customer("cust-bob"), "bob isolated");

        // Bob cannot fetch Alice's order — and cannot learn that it exists.
        mockMvc.perform(get(BASE + "/" + aliceOrder).with(customer("cust-bob")))
                .andExpect(status().isNotFound());

        // Bob's list never contains Alice's order.
        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + aliceOrder + "')]").isEmpty());

        // Staff sees everything.
        mockMvc.perform(get(BASE + "/" + aliceOrder).with(staff()))
                .andExpect(status().isOk());
    }

    @Test
    void customerMayCancelOwnOrder_butNothingElse() throws Exception {
        String order = createOrderAs(customer("cust-carol"), "carol order");

        // Editing anything but state=cancelled is rejected.
        mockMvc.perform(patch(BASE + "/" + order).with(customer("cust-carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"rewritten\"}"))
                .andExpect(status().isBadRequest());

        // Deleting is a back-office operation.
        mockMvc.perform(delete(BASE + "/" + order).with(customer("cust-carol")))
                .andExpect(status().isBadRequest());

        // Cancelling is allowed.
        mockMvc.perform(patch(BASE + "/" + order).with(customer("cust-carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("cancelled"));

        // But not cancelling someone else's order.
        String other = createOrderAs(customer("cust-dave"), "dave order");
        mockMvc.perform(patch(BASE + "/" + other).with(customer("cust-carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"cancelled\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffOrderForCustomer_derivesOwnerFromRelatedParty() throws Exception {
        mockMvc.perform(post(BASE).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "csr order", "productOrderItem": [{"id": "1", "action": "add"}],
                                 "relatedParty": [{"id": "cust-eve", "role": "Customer"}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-eve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].description").value(hasItem("csr order")));
    }
}
