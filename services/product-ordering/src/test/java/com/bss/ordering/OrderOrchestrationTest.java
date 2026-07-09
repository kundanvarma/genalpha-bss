package com.bss.ordering;

import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.exception.DownstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Order-to-cash orchestration behavior, with the three downstream services
 * mocked at the client-interface boundary: reference validation at creation,
 * inventory provisioning at completion, terminal-state protection, and
 * downstream-failure mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderOrchestrationTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private PartyClient partyClient;

    @MockBean
    private InventoryClient inventoryClient;

    @Test
    void create_rejectsUnknownProductOffering() throws Exception {
        given(catalogClient.findOffering("missing-po")).willReturn(Optional.empty());

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "productOfferingId": "missing-po"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void create_rejectsUnknownBillingAccount() throws Exception {
        given(catalogClient.findOffering(anyString()))
                .willReturn(Optional.of(new CatalogClient.OfferingRef("po-1", "5G Plan")));
        given(partyClient.billingAccountExists("missing-ba")).willReturn(false);

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "productOfferingId": "po-1", "billingAccountId": "missing-ba"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void create_withValidReferences_isAcknowledgedAndSetsOrderDate() throws Exception {
        given(catalogClient.findOffering("po-1"))
                .willReturn(Optional.of(new CatalogClient.OfferingRef("po-1", "5G Plan")));
        given(partyClient.billingAccountExists("ba-1")).willReturn(true);

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "productOfferingId": "po-1", "billingAccountId": "ba-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billingAccountId").value("ba-1"))
                .andExpect(jsonPath("$.orderDate").exists());
        verify(catalogClient).findOffering("po-1");
        verify(partyClient).billingAccountExists("ba-1");
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void create_withoutReferences_makesNoDownstreamCalls() throws Exception {
        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged"}
                                """))
                .andExpect(status().isCreated());
        verifyNoInteractions(catalogClient, partyClient, inventoryClient);
    }

    @Test
    void completingAnOrder_provisionsTheProductIntoInventory() throws Exception {
        given(catalogClient.findOffering(anyString()))
                .willReturn(Optional.of(new CatalogClient.OfferingRef("po-1", "5G Plan")));
        given(partyClient.billingAccountExists(anyString())).willReturn(true);
        String id = createOrder("""
                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "description": "Fibre 1G subscription",
                 "productOfferingId": "po-1", "billingAccountId": "ba-1"}
                """);

        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state": "completed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"));

        ArgumentCaptor<InventoryClient.NewProduct> product =
                ArgumentCaptor.forClass(InventoryClient.NewProduct.class);
        verify(inventoryClient).createProduct(product.capture());
        assertThat(product.getValue().name()).isEqualTo("Fibre 1G subscription");
        assertThat(product.getValue().status()).isEqualTo("active");
        assertThat(product.getValue().productOffering()).isEqualTo(java.util.Map.of("id", "po-1"));
        assertThat(product.getValue().billingAccount()).isEqualTo(java.util.Map.of("id", "ba-1"));
    }

    @Test
    void completedOrder_isTerminal_andProvisionsOnlyOnce() throws Exception {
        String id = createOrder("""
                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "description": "One-shot order"}
                """);
        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state": "completed"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "post-completion edit"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verify(inventoryClient, times(1)).createProduct(any());
    }

    @Test
    void completion_whenInventoryFails_returns502AndOrderStaysUncompleted() throws Exception {
        String id = createOrder("""
                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "description": "Doomed order"}
                """);
        willThrow(new DownstreamException("product-inventory rejected or is unreachable", new RuntimeException()))
                .given(inventoryClient).createProduct(any());

        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state": "completed"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("502"));

        mockMvc.perform(get(BASE + "/" + id).with(readToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("acknowledged"));
    }

    @Test
    void create_whenCatalogUnreachable_returns502() throws Exception {
        given(catalogClient.findOffering(anyString()))
                .willThrow(new DownstreamException("product-catalog is unreachable", new RuntimeException()));

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "productOfferingId": "po-1"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("502"));
    }

    private String createOrder(String body) throws Exception {
        String response = mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private static RequestPostProcessor readToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:read"));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
