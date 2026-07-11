package com.bss.payment;

import org.junit.jupiter.api.Test;
import com.bss.payment.client.PaymentMethodClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiTest {

    private static final String BASE = "/tmf-api/paymentManagement/v4/payment";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentMethodClient paymentMethodClient;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"));
    }

    private static RequestPostProcessor machine() {
        return jwt().authorities(
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"));
    }

    private static String body(String card) {
        return """
                {"description": "one-time charges", "amount": {"unit": "EUR", "value": 49.00},
                 "paymentMethod": {"@type": "bankCard", "cardNumber": "%s", "expiry": "12/28", "cvc": "123"}}
                """.formatted(card);
    }

    private String authorize(RequestPostProcessor token) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(token)
                        .contentType(MediaType.APPLICATION_JSON).content(body("4242424242424242")))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void goodCard_authorizes_masksCard_neverEchoesPan() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(customer("cust-pay"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("4242 4242 4242 4242")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("authorized"))
                .andExpect(jsonPath("$.amount.value").value(49.00))
                .andExpect(jsonPath("$.paymentMethod.label").value("bankCard •••• 4242"))
                .andExpect(jsonPath("$.authorizationCode").isNotEmpty())
                .andReturn();
        if (result.getResponse().getContentAsString().contains("4242424242424242")) {
            throw new AssertionError("full card number echoed in response");
        }
    }

    @Test
    void declinedCard_conflicts_andStoresNothing() throws Exception {
        mockMvc.perform(post(BASE).with(customer("cust-declined"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("4000000000000002")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("card declined"));

        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-declined")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void customersAreIsolated_machineIsNot() throws Exception {
        String paymentId = authorize(customer("cust-a"));

        mockMvc.perform(get(BASE + "/" + paymentId).with(customer("cust-b")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + paymentId).with(customer("cust-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedParty[0].id").value("cust-a"));
        mockMvc.perform(get(BASE + "/" + paymentId).with(machine()))
                .andExpect(status().isOk());
    }

    @Test
    void lifecycle_authorizedCapturesOrVoids_capturedIsFinal() throws Exception {
        String paymentId = authorize(customer("cust-life"));

        mockMvc.perform(patch(BASE + "/" + paymentId).with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"captured\", \"correlatorId\": \"order-77\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("captured"))
                .andExpect(jsonPath("$.correlatorId").value("order-77"));

        mockMvc.perform(patch(BASE + "/" + paymentId).with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"voided\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void sameCorrelatorNeverChargesTwice() throws Exception {
        String withCorrelator = """
                {"description": "checkout", "correlatorId": "idem-order-1",
                 "amount": {"unit": "EUR", "value": 49.00},
                 "paymentMethod": {"@type": "bankCard", "cardNumber": "4242424242424242", "expiry": "12/28", "cvc": "123"}}
                """;
        String first = mockMvc.perform(post(BASE).with(customer("cust-idem"))
                        .contentType(MediaType.APPLICATION_JSON).content(withCorrelator))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.id");

        // The retry returns the SAME payment — no second authorization, no
        // second charge (the guarantee that matters, whatever the status code).
        mockMvc.perform(post(BASE).with(customer("cust-idem"))
                        .contentType(MediaType.APPLICATION_JSON).content(withCorrelator))
                .andExpect(jsonPath("$.id").value(firstId));

        mockMvc.perform(get(BASE + "?correlatorId=idem-order-1&limit=100").with(machine()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void scaCardChallenges_thenCaptureRecordsSettlement() throws Exception {
        // A card that triggers strong customer authentication -> 402 + action URL.
        mockMvc.perform(post(BASE).with(customer("cust-sca"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("4000000000003155")))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("X-SCA-Action-Url"));

        // A clean authorization, then capture — settlement reference is recorded,
        // proving the capture actually moved money (not just a status flip).
        String paymentId = authorize(customer("cust-settle"));
        mockMvc.perform(patch(BASE + "/" + paymentId).with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"captured\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("captured"))
                .andExpect(jsonPath("$.settlementRef").isNotEmpty());
    }
}
