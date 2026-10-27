package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.ChoicesClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.PolicyClient;
import com.bss.ordering.client.PromotionClient;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Swap and release (#356): one order ends a benefit and picks another; the
 * new pick may spend the Choice the ending frees; the old benefit ends only
 * when the new one lands (A7) — never if it fails; a release gives the
 * Choice back; and the swap-limit rule sees how many swaps were made.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderSwapTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private ChoicesClient choices;

    @MockitoBean
    private PolicyClient policy;

    @MockitoBean
    private AgreementClient agreementClient;

    @MockitoBean
    private PromotionClient promotionClient;

    @MockitoBean
    private PartyClient partyClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @BeforeEach
    void stubs() {
        given(catalogClient.findOffering(anyString()))
                .willAnswer(i -> Optional.of(new CatalogClient.OfferingRef(i.getArgument(0), i.getArgument(0))));
        given(catalogClient.findOfferingDetail("po-stream")).willReturn(Optional.of(Map.of(
                "id", "po-stream", "name", "StreamCo Standard", "lifecycleStatus", "Launched", "choiceCost", 1)));
        given(catalogClient.findOfferingDetail("po-audio")).willReturn(Optional.of(Map.of(
                "id", "po-audio", "name", "Lydbok+ Audiobooks", "lifecycleStatus", "Launched", "choiceCost", 1)));
        given(inventoryClient.getProduct("prod-old")).willReturn(Optional.of(Map.of(
                "id", "prod-old", "name", "StreamCo Standard", "status", "active",
                "productOffering", Map.of("id", "po-stream", "name", "StreamCo Standard"),
                "relatedParty", List.of(Map.of("id", "cust-1", "role", "customer")),
                "productOrderItem", List.of(Map.of("productOrderId", "ord-old", "orderItemId", "1")))));
        given(choices.hold(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .willReturn(ChoicesClient.HoldOutcome.held());
        given(choices.pool(anyString())).willReturn(new ChoicesClient.Pool(1, 1, List.of(),
                List.of(new ChoicesClient.InUse("StreamCo Standard", 1, "committed", "ord-old", "1", null)), 1, "2027-01-01"));
        given(policy.evaluateOrder(any())).willReturn(PolicyClient.Decision.allow());
    }

    private String swapOrder() throws Exception {
        String body = mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"relatedParty": [{"id": "cust-1", "role": "customer"}], "productOrderItem": [
                          {"id": "1", "action": "delete", "product": {"id": "prod-old"}},
                          {"id": "2", "action": "add", "productOffering": {"id": "po-audio", "name": "Lydbok+ Audiobooks"}}]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("acknowledged"))   // a swap waits for its pick
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }

    private void item(String id, String itemId, String state) throws Exception {
        mockMvc.perform(patch(BASE + "/" + id + "/productOrderItem/" + itemId).with(token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"" + state + "\"}")).andExpect(status().isOk());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSwap_holdsWithTheChoiceItFrees_andEndsTheOldBenefitOnlyWhenTheNewOneLands() throws Exception {
        String id = swapOrder();
        verify(choices).hold("cust-1", id, "2", "po-audio", "Lydbok+ Audiobooks", 1,
                List.of(new ChoicesClient.Source("ord-old", "1")), ChoicesClient.Terms.NONE);
        verify(inventoryClient, never()).updateProduct(eq("prod-old"), any());   // not yet

        item(id, "2", "completed");
        mockMvc.perform(get(BASE + "/" + id).with(token())).andExpect(jsonPath("$.state").value("completed"));
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(inventoryClient).updateProduct(eq("prod-old"), patch.capture());
        assertThat(patch.getValue()).containsEntry("status", "cancelled");
        verify(choices).settle(eq(id), eq("cust-1"), eq(List.of()),
                eq(List.of(new ChoicesClient.Ending("ord-old", "1", true))), eq(List.of()));
    }

    @Test
    void aSwapWhosePickFails_keepsTheOldBenefit_andGivesTheNewHoldBack() throws Exception {
        String id = swapOrder();
        item(id, "2", "failed");
        mockMvc.perform(get(BASE + "/" + id).with(token()))
                .andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.productOrderItem[0].state").value("cancelled"));
        verify(inventoryClient, never()).updateProduct(eq("prod-old"), any());
        verify(choices, never()).settle(anyString(), anyString(), any(), any(), any());
        verify(choices).release(id);
    }

    @Test
    void aRelease_givesTheChoiceBack_andIsNotASwap() throws Exception {
        String body = mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"relatedParty": [{"id": "cust-1", "role": "customer"}],
                         "productOrderItem": [{"id": "1", "action": "delete", "product": {"id": "prod-old"}}]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("completed"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asString();
        verify(choices).settle(eq(id), eq("cust-1"), eq(List.of()),
                eq(List.of(new ChoicesClient.Ending("ord-old", "1", false))), eq(List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSwapLimitRule_seesTheSwapsAndWhenTheNextQuarterStarts() throws Exception {
        swapOrder();
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(policy).evaluateOrder(context.capture());
        assertThat(context.getValue()).containsEntry("choiceSwap", true)
                .containsEntry("choiceSwapsThisQuarter", 1).containsEntry("nextQuarterStarts", "2027-01-01");
    }

    private static RequestPostProcessor token() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"), new SimpleGrantedAuthority("ordering:read"));
    }
}
