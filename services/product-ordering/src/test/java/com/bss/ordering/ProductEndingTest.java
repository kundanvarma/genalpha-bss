package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.PromotionClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ending a product (TMF622 action=delete, #353): the item names an installed
 * product; ordering checks it is active, the customer's own and free of a
 * commitment, completes the item from inventory, and closes the product —
 * adding nothing (no availability check, no new commitment).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductEndingTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private AgreementClient agreementClient;

    @MockitoBean
    private PromotionClient promotionClient;

    @MockitoBean
    private PartyClient partyClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    private static Map<String, Object> product(String status, String owner) {
        return Map.of(
                "id", "prod-1", "name", "StreamCo Standard", "status", status,
                "productOffering", Map.of("id", "po-stream", "name", "StreamCo Standard"),
                "relatedParty", List.of(Map.of("id", owner, "role", "customer")),
                "realizingService", List.of(Map.of("id", "svc-9")));
    }

    private static String deleteOrder(String productJson) {
        return """
                {"relatedParty": [{"id": "cust-1", "role": "customer"}],
                 "productOrderItem": [{"id": "1", "action": "delete", "product": %s}]}
                """.formatted(productJson);
    }

    @Test
    @SuppressWarnings("unchecked")
    void endingAnActiveProduct_completesAndClosesItInInventory() throws Exception {
        given(inventoryClient.getProduct("prod-1")).willReturn(Optional.of(product("active", "cust-1")));

        mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(deleteOrder("{\"id\": \"prod-1\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("completed"))
                // the item is completed from inventory: what is ended, and the service behind it
                .andExpect(jsonPath("$.productOrderItem[0].productOffering.id").value("po-stream"))
                .andExpect(jsonPath("$.productOrderItem[0].product.realizingService[0].id").value("svc-9"));

        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(inventoryClient).updateProduct(eq("prod-1"), patch.capture());
        assertThat(patch.getValue()).containsEntry("status", "cancelled").containsKey("terminationDate");
        verify(inventoryClient, never()).createProduct(any());
        // an ending adds nothing: no availability check (a retired offer can still be ended)
        verify(catalogClient, never()).findOffering(anyString());
        verify(agreementClient, never()).activate(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void endingSomeoneElsesProduct_isRefused() throws Exception {
        given(inventoryClient.getProduct("prod-1")).willReturn(Optional.of(product("active", "cust-2")));

        mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(deleteOrder("{\"id\": \"prod-1\"}")))
                .andExpect(status().isBadRequest());
        verify(inventoryClient, never()).updateProduct(anyString(), any());
    }

    @Test
    void endingAProductThatIsNotActive_saysThereIsNothingToEnd() throws Exception {
        given(inventoryClient.getProduct("prod-1")).willReturn(Optional.of(product("cancelled", "cust-1")));

        mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(deleteOrder("{\"id\": \"prod-1\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("nothing to end")));
    }

    @Test
    void aDeleteItemWithoutAProduct_isRefused() throws Exception {
        mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(deleteOrder("{}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOpenCommitment_blocksTheEnding() throws Exception {
        given(inventoryClient.getProduct("prod-1")).willReturn(Optional.of(product("active", "cust-1")));
        given(agreementClient.activeAgreements("cust-1")).willReturn(List.of(Map.of(
                "agreementPeriod", Map.of("endDateTime", java.time.OffsetDateTime.now().plusMonths(3).toString()),
                "agreementItem", List.of(Map.of("productOffering", Map.of("id", "po-stream"))))));

        mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(deleteOrder("{\"id\": \"prod-1\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("under a commitment")));
        verify(inventoryClient, never()).updateProduct(anyString(), any());
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
