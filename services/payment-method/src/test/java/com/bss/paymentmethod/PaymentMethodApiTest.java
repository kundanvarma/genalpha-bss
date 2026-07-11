package com.bss.paymentmethod;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentMethodApiTest {

    private static final String BASE = "/tmf-api/paymentMethods/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("paymentmethod:read"),
                new SimpleGrantedAuthority("paymentmethod:write"));
    }

    private static RequestPostProcessor machine() {
        return jwt().authorities(
                new SimpleGrantedAuthority("paymentmethod:read"),
                new SimpleGrantedAuthority("paymentmethod:write"));
    }

    private String save(String sub) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/paymentMethod").with(customer(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"@type": "bankCard", "preferred": true,
                                 "details": {"brand": "visa", "lastFourDigits": "4242", "expiry": "01/29"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.details.lastFourDigits").value("4242"))
                .andExpect(jsonPath("$.details.token").isNotEmpty())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void vaultIsPartyScoped_listsNeverExposeTheToken() throws Exception {
        String id = save("cust-m1");

        // Own listing works — but the vault token stays machine-only.
        mockMvc.perform(get(BASE + "/paymentMethod").with(customer("cust-m1")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].details.lastFourDigits").value("4242"))
                .andExpect(jsonPath("$[0].details.token").doesNotExist());

        // Foreign customers see nothing, resolve reads as absent.
        mockMvc.perform(get(BASE + "/paymentMethod").with(customer("cust-nosy")))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(BASE + "/paymentMethod/" + id).with(customer("cust-nosy")))
                .andExpect(status().isNotFound());

        // The machine (payment service) resolves with the token.
        mockMvc.perform(get(BASE + "/paymentMethod/" + id).with(machine()))
                .andExpect(jsonPath("$.details.token").isNotEmpty());
    }

    @Test
    void deletedMethodsAreGoneForGood() throws Exception {
        String id = save("cust-m2");
        mockMvc.perform(delete(BASE + "/paymentMethod/" + id).with(customer("cust-m2")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(BASE + "/paymentMethod").with(customer("cust-m2")))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(BASE + "/paymentMethod/" + id).with(customer("cust-m2")))
                .andExpect(status().isNotFound());
    }
}
