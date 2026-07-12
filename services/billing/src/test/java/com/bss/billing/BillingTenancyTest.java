package com.bss.billing;

import com.bss.billing.client.DownstreamClients;
import com.jayway.jsonpath.JsonPath;
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

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: bills belong to the tenant of the verified token issuer
 * that triggered the billing run. Cross-tenant access must behave as if the
 * bill does not exist (404, never 403), and lists must never leak foreign
 * rows — even for the same party id under a different tenant's issuer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingTenancyTest {

    private static final String BASE = "/tmf-api/customerBillManagement/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

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

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"));
    }

    private void mockCatalogAndInventory(String owner) {
        given(inventoryClient.activeProducts()).willReturn(List.of(
                Map.of("id", "prod-1", "name", "GenAlpha Fiber 1000",
                        "productOffering", Map.of("id", "po-fiber"),
                        "relatedParty", List.of(Map.of("id", owner, "role", "customer")))));
        given(catalogClient.offering("po-fiber")).willReturn(
                Map.of("id", "po-fiber", "productOfferingPrice", List.of(Map.of("id", "price-fiber"))));
        given(catalogClient.price("price-fiber")).willReturn(
                Map.of("priceType", "recurring", "price", Map.of("unit", "EUR", "value", 39.99)));
    }

    private String runAndGetBillId(String issuer, String owner) throws Exception {
        mockCatalogAndInventory(owner);
        mockMvc.perform(post(BASE + "/billingRun").with(staffOf(issuer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billsCreated").value(1));
        MvcResult result = mockMvc.perform(get(BASE + "/customerBill?limit=100")
                        .with(customerOf(issuer, owner)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[0].id");
    }

    @Test
    void tenantsCannotSeeEachOthersBills() throws Exception {
        String billIdA = runAndGetBillId(ISSUER_A, "cust-ten-1");

        // The owning tenant reads it back; the other tenant gets 404, not 403 —
        // even the same party id and the other tenant's staff.
        mockMvc.perform(get(BASE + "/customerBill/" + billIdA).with(customerOf(ISSUER_A, "cust-ten-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("new"));
        mockMvc.perform(get(BASE + "/customerBill/" + billIdA).with(customerOf(ISSUER_B, "cust-ten-1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/customerBill/" + billIdA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/customerBill/" + billIdA + "/appliedCustomerBillingRate")
                        .with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "/customerBill?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(billIdA)) {
            throw new AssertionError("tenant B list leaked tenant A's bill");
        }
        mockMvc.perform(get(BASE + "/customerBill?limit=100").with(customerOf(ISSUER_B, "cust-ten-1")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void writesAndRunsAreConfinedToTheCallersTenant() throws Exception {
        String billIdA = runAndGetBillId(ISSUER_A, "cust-ten-2");

        // Settling a foreign tenant's bill reads as 404.
        mockMvc.perform(patch(BASE + "/customerBill/" + billIdA).with(customerOf(ISSUER_B, "cust-ten-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"settled\", \"payment\": [{\"id\": \"pay-1\"}]}"))
                .andExpect(status().isNotFound());

        // The per-period dedup is tenant-scoped: tenant B running for the same
        // customer in the same period cuts its own bill instead of being skipped.
        mockMvc.perform(post(BASE + "/billingRun").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billsCreated").value(1))
                .andExpect(jsonPath("$.customersSkipped").value(0));

        // Tenant A's original bill is still intact and unsettled.
        mockMvc.perform(get(BASE + "/customerBill/" + billIdA).with(customerOf(ISSUER_A, "cust-ten-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("new"));
    }
}
