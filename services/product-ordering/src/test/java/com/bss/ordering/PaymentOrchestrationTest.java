package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.PaymentClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.StockClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payment follows the order: refs are validated (authorized + right owner) at
 * create, captured on completion, voided on cancellation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentOrchestrationTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentClient paymentClient;

    @MockBean
    private AgreementClient agreementClient;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private StockClient stockClient;

    @MockBean
    private InventoryClient inventoryClient;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private static final String PAID_ORDER = """
            {"description": "paid order",
             "payment": [{"id": "pay-1", "@referredType": "Payment"}],
             "productOrderItem": [{"id": "1", "action": "add",
               "productOffering": {"id": "po-x", "name": "Thing"}}]}
            """;

    private void stockOk() {
        given(stockClient.reserve(anyString(), anyString(), anyInt(), anyString()))
                .willReturn(StockClient.ReserveOutcome.reserved());
    }

    @Test
    void createValidatesPaymentRef_withOwner() throws Exception {
        stockOk();
        given(paymentClient.validateAuthorized(eq("pay-1"), eq("cust-payer"), anyString())).willReturn("");

        mockMvc.perform(post(BASE).with(customer("cust-payer"))
                        .contentType(MediaType.APPLICATION_JSON).content(PAID_ORDER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment[0].id").value("pay-1"));

        verify(paymentClient).validateAuthorized(eq("pay-1"), eq("cust-payer"), anyString());
    }

    @Test
    void badPaymentRef_sinksTheOrder() throws Exception {
        stockOk();
        given(paymentClient.validateAuthorized(anyString(), anyString(), anyString()))
                .willReturn("payment 'pay-1' belongs to another party");

        mockMvc.perform(post(BASE).with(customer("cust-thief"))
                        .contentType(MediaType.APPLICATION_JSON).content(PAID_ORDER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("payment 'pay-1' belongs to another party"));
    }

    @Test
    void completionCaptures_cancellationVoids() throws Exception {
        stockOk();
        given(paymentClient.validateAuthorized(anyString(), anyString(), anyString())).willReturn("");

        String completed = createOrder("cust-cap");
        mockMvc.perform(patch(BASE + "/" + completed).with(customer("cust-cap"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"completed\"}"))
                .andExpect(status().isBadRequest()); // customers cannot complete
        mockMvc.perform(patch(BASE + "/" + completed).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"completed\"}"))
                .andExpect(status().isOk());
        verify(paymentClient).capture("pay-1");

        String cancelled = createOrder("cust-void");
        mockMvc.perform(patch(BASE + "/" + cancelled).with(customer("cust-void"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"cancelled\"}"))
                .andExpect(status().isOk());
        verify(paymentClient).voidPayment("pay-1");
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private String createOrder(String sub) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(customer(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(PAID_ORDER))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
