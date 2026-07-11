package com.bss.ordering;

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
 * Order lifecycle drives stock: create reserves every item (top-level and
 * nested), completion consumes, cancellation releases, and an insufficient
 * reservation sinks the order with compensation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockOrchestrationTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StockClient stockClient;

    @MockBean
    private InventoryClient inventoryClient;

    @MockBean
    private com.bss.ordering.client.CatalogClient catalogClient;

    @org.junit.jupiter.api.BeforeEach
    void stubCatalog() {
        given(catalogClient.findOffering(anyString())).willReturn(java.util.Optional.of(
                new com.bss.ordering.client.CatalogClient.OfferingRef("po", "Offering", null, false)));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private static final String CART_ORDER = """
            {"description": "bundle with phone", "productOrderItem": [
              {"id": "1", "action": "add", "quantity": 2,
               "productOffering": {"id": "po-bundle", "name": "Bundle"},
               "productOrderItem": [
                 {"id": "1.1", "action": "add", "quantity": 2,
                  "productOffering": {"id": "po-phone", "name": "Phone"}}
               ]}
            ]}
            """;

    @Test
    void createReservesEveryItem_nestedIncluded() throws Exception {
        given(stockClient.reserve(anyString(), anyString(), anyInt(), anyString()))
                .willReturn(StockClient.ReserveOutcome.reserved());

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(CART_ORDER))
                .andExpect(status().isCreated());

        verify(stockClient).reserve(eq("po-bundle"), eq("Bundle"), eq(2), anyString());
        verify(stockClient).reserve(eq("po-phone"), eq("Phone"), eq(2), anyString());
    }

    @Test
    void insufficientStock_failsTheOrder_andCompensates() throws Exception {
        given(stockClient.reserve(eq("po-bundle"), anyString(), anyInt(), anyString()))
                .willReturn(StockClient.ReserveOutcome.reserved());
        given(stockClient.reserve(eq("po-phone"), anyString(), anyInt(), anyString()))
                .willReturn(StockClient.ReserveOutcome.insufficient("insufficient stock for 'Phone'"));

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(CART_ORDER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("insufficient stock for 'Phone'"));

        verify(stockClient).release(anyString());
    }

    @Test
    void completionConsumes_cancellationReleases() throws Exception {
        given(stockClient.reserve(anyString(), anyString(), anyInt(), anyString()))
                .willReturn(StockClient.ReserveOutcome.reserved());

        String completed = createOrder();
        mockMvc.perform(patch(BASE + "/" + completed).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"completed\"}"))
                .andExpect(status().isOk());
        verify(stockClient).consume(completed);

        String cancelled = createOrder();
        mockMvc.perform(patch(BASE + "/" + cancelled).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"cancelled\"}"))
                .andExpect(status().isOk());
        verify(stockClient).release(cancelled);
    }

    private String createOrder() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(CART_ORDER))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
