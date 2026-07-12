package com.bss.billing;

import com.bss.billing.client.DownstreamClients;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingApiTest {

    private static final String BASE = "/tmf-api/customerBillManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DownstreamClients.InventoryClient inventoryClient;

    @MockBean
    private DownstreamClients.CatalogClient catalogClient;

    @MockBean
    private DownstreamClients.PaymentClient paymentClient;

    @MockBean
    private DownstreamClients.UsageClient usageClient;

    @MockBean
    private DownstreamClients.PromotionClient promotionClient;

    @MockBean
    private DownstreamClients.PricingClient pricingClient;

    @MockBean
    private DownstreamClients.OrgClient orgClient;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"));
    }

    private void mockCatalogAndInventory(String owner) {
        given(inventoryClient.activeProducts()).willReturn(List.of(
                Map.of("id", "prod-1", "name", "GenAlpha Fiber 1000",
                        "productOffering", Map.of("id", "po-fiber"),
                        "relatedParty", List.of(Map.of("id", owner, "role", "customer"))),
                Map.of("id", "prod-2", "name", "GenAlpha TV Max",
                        "productOffering", Map.of("id", "po-tv"),
                        "relatedParty", List.of(Map.of("id", owner, "role", "customer")))));
        given(catalogClient.offering("po-fiber")).willReturn(
                Map.of("id", "po-fiber", "productOfferingPrice", List.of(Map.of("id", "price-fiber"))));
        given(catalogClient.offering("po-tv")).willReturn(
                Map.of("id", "po-tv", "productOfferingPrice", List.of(Map.of("id", "price-tv"))));
        given(catalogClient.price("price-fiber")).willReturn(
                Map.of("priceType", "recurring", "price", Map.of("unit", "EUR", "value", 39.99)));
        given(catalogClient.price("price-tv")).willReturn(
                Map.of("priceType", "recurring", "price", Map.of("unit", "EUR", "value", 14.99)));
    }

    private String runAndGetBillId(String owner) throws Exception {
        mockCatalogAndInventory(owner);
        mockMvc.perform(post(BASE + "/billingRun").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billsCreated").value(1));
        MvcResult result = mockMvc.perform(get(BASE + "/customerBill?limit=100").with(customer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$[0].id");
    }

    @Test
    void billingRun_ratesActiveProducts_andIsIdempotentPerPeriod() throws Exception {
        String billId = runAndGetBillId("cust-run");

        mockMvc.perform(get(BASE + "/customerBill/" + billId).with(customer("cust-run")))
                .andExpect(jsonPath("$.state").value("new"))
                .andExpect(jsonPath("$.amountDue.value").value(54.98))
                .andExpect(jsonPath("$.billNo").isNotEmpty());

        mockMvc.perform(get(BASE + "/customerBill/" + billId + "/appliedCustomerBillingRate")
                        .with(customer("cust-run")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.hasItem("recurringCharge")));

        // Second run in the same period bills nobody twice.
        mockMvc.perform(post(BASE + "/billingRun").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billsCreated").value(0))
                .andExpect(jsonPath("$.customersSkipped").value(1));
    }

    @Test
    void billingRun_addsRatedUsageChargesToTheBill() throws Exception {
        given(usageClient.rateForParty(org.mockito.ArgumentMatchers.eq("cust-usage"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(List.of(Map.of(
                        "name", "EU roaming data overage: 2.3 GB over 10 GB included",
                        "amount", Map.of("unit", "EUR", "value", 5.75))));

        String billId = runAndGetBillId("cust-usage");

        mockMvc.perform(get(BASE + "/customerBill/" + billId).with(customer("cust-usage")))
                .andExpect(jsonPath("$.amountDue.value").value(60.73));
        mockMvc.perform(get(BASE + "/customerBill/" + billId + "/appliedCustomerBillingRate")
                        .with(customer("cust-usage")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.hasItem("usageCharge")));
    }

    @Test
    void billingRun_appliesEarnedPromotionDiscounts() throws Exception {
        given(promotionClient.redemptionsFor("cust-promo")).willReturn(List.of(Map.of(
                "code", "WELCOME10", "name", "Welcome offer", "percentage", 10,
                "appliesTo", List.of("po-fiber"))));

        String billId = runAndGetBillId("cust-promo");

        // 10% off the fiber's 39.99 = -4.00; 54.98 - 4.00 = 50.98
        mockMvc.perform(get(BASE + "/customerBill/" + billId).with(customer("cust-promo")))
                .andExpect(jsonPath("$.amountDue.value").value(50.98));
        mockMvc.perform(get(BASE + "/customerBill/" + billId + "/appliedCustomerBillingRate")
                        .with(customer("cust-promo")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.hasItem("discount")));
    }

    @Test
    void customersAreIsolated_andCannotTriggerRuns() throws Exception {
        String billId = runAndGetBillId("cust-iso");

        mockMvc.perform(get(BASE + "/customerBill/" + billId).with(customer("cust-other")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(BASE + "/billingRun").with(customer("cust-iso")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settlingABill_validatesAndCapturesThePayment() throws Exception {
        String billId = runAndGetBillId("cust-settle");
        given(paymentClient.validateAuthorized(eq("pay-9"), eq("cust-settle"), any(BigDecimal.class)))
                .willReturn("");

        mockMvc.perform(patch(BASE + "/customerBill/" + billId).with(customer("cust-settle"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"settled\", \"payment\": [{\"id\": \"pay-9\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("settled"));
        verify(paymentClient).capture("pay-9");

        // Settling twice conflicts.
        mockMvc.perform(patch(BASE + "/customerBill/" + billId).with(customer("cust-settle"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"settled\", \"payment\": [{\"id\": \"pay-9\"}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void settlingWithABadPayment_conflicts() throws Exception {
        String billId = runAndGetBillId("cust-badpay");
        given(paymentClient.validateAuthorized(anyString(), anyString(), any(BigDecimal.class)))
                .willReturn("payment 'pay-x' covers 1.00, bill needs 54.98");

        mockMvc.perform(patch(BASE + "/customerBill/" + billId).with(customer("cust-badpay"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"settled\", \"payment\": [{\"id\": \"pay-x\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("payment 'pay-x' covers 1.00, bill needs 54.98"));
    }
}
