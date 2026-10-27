package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.PromotionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An item that fails (#355 — a partner refused the activation) is a final
 * state: an order where nothing landed is {@code failed} and provisions
 * nothing; one where some items landed is {@code partial} and provisions only
 * what was delivered (TMF622 order states).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderFailureTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @BeforeEach
    void catalog() {
        given(catalogClient.findOffering(anyString()))
                .willAnswer(i -> Optional.of(new CatalogClient.OfferingRef(i.getArgument(0), i.getArgument(0))));
    }

    private String order(String... offerings) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < offerings.length; i++) {
            items.append(i == 0 ? "" : ",").append("{\"id\": \"").append(i + 1)
                    .append("\", \"action\": \"add\", \"productOffering\": {\"id\": \"").append(offerings[i])
                    .append("\", \"name\": \"").append(offerings[i]).append("\"}}");
        }
        String body = mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relatedParty\": [{\"id\": \"cust-1\", \"role\": \"customer\"}], \"productOrderItem\": ["
                                + items + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }

    private void item(String orderId, String itemId, String state) throws Exception {
        mockMvc.perform(patch(BASE + "/" + orderId + "/productOrderItem/" + itemId).with(token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"" + state + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anOrderWhoseOnlyItemFailed_isFailed_andProvisionsNothing() throws Exception {
        String id = order("StreamCo Standard");
        item(id, "1", "failed");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/" + id)
                        .with(token()))
                .andExpect(jsonPath("$.state").value("failed"));
        verify(inventoryClient, never()).createProduct(any());
    }

    @Test
    void anOrderWithOneFailedAndOneDeliveredItem_isPartial_andProvisionsOnlyTheDeliveredOne() throws Exception {
        String id = order("StreamCo Standard", "Data Top-up");
        item(id, "1", "failed");
        item(id, "2", "completed");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/" + id)
                        .with(token()))
                .andExpect(jsonPath("$.state").value("partial"));
        verify(inventoryClient, times(1)).createProduct(any());
    }

    private static RequestPostProcessor token() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"), new SimpleGrantedAuthority("ordering:read"));
    }
}
