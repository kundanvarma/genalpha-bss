package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.ChoicesClient;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where an order meets the household's Choices (#354): a benefit on the order
 * is held at placement (409 when too few are left), a completing order
 * settles — committing its holds and adding what its plans grant — and an
 * order with neither never calls the ledger.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderChoicesTest {

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
    private AgreementClient agreementClient;

    @MockitoBean
    private PromotionClient promotionClient;

    @MockitoBean
    private PartyClient partyClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @BeforeEach
    void catalog() {
        given(catalogClient.findOffering("po-stream")).willReturn(Optional.of(new CatalogClient.OfferingRef("po-stream", "StreamCo Standard")));
        given(catalogClient.findOffering("po-plan")).willReturn(Optional.of(new CatalogClient.OfferingRef("po-plan", "Mobile Max")));
        given(catalogClient.findOffering("po-plain")).willReturn(Optional.of(new CatalogClient.OfferingRef("po-plain", "Data Top-up")));
        given(catalogClient.findOfferingDetail("po-stream")).willReturn(Optional.of(Map.of("id", "po-stream", "name", "StreamCo Standard", "lifecycleStatus", "Launched", "choiceCost", 1)));
        given(catalogClient.findOfferingDetail("po-plan")).willReturn(Optional.of(Map.of("id", "po-plan", "name", "Mobile Max", "lifecycleStatus", "Launched", "choicesGranted", 2)));
        given(catalogClient.findOfferingDetail("po-plain")).willReturn(Optional.of(Map.of("id", "po-plain", "name", "Data Top-up", "lifecycleStatus", "Launched")));
    }

    private static String order(String offeringId) {
        return """
                {"relatedParty": [{"id": "cust-1", "role": "customer"}],
                 "productOrderItem": [{"id": "1", "action": "add", "productOffering": {"id": "%s"}}]}
                """.formatted(offeringId);
    }

    @Test
    void aBenefitOnTheOrder_holdsItsCost_forTheCustomer() throws Exception {
        given(choices.hold(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ChoicesClient.HoldOutcome.held());
        String body = mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(order("po-stream")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(body).get("id").asString();
        verify(choices).hold("cust-1", orderId, "1", "po-stream", "StreamCo Standard", 1, List.of());
    }

    @Test
    void tooFewChoicesLeft_refusesTheOrderWith409_andHoldsNothing() throws Exception {
        given(choices.hold(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ChoicesClient.HoldOutcome.refused("StreamCo Standard costs 1 of your Choices and you have 0 left"));
        mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(order("po-stream")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("StreamCo Standard costs 1 of your Choices and you have 0 left"));
        verify(choices).release(anyString());
    }

    @Test
    void aCompletingPlan_settlesWithTheChoicesItGrants() throws Exception {
        String body = mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(order("po-plan")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(body).get("id").asString();
        verify(choices, never()).hold(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), any());

        mockMvc.perform(patch(BASE + "/" + orderId).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"completed\"}"))
                .andExpect(status().isOk());
        verify(choices).settle(eq(orderId), eq("cust-1"),
                eq(List.of(new ChoicesClient.Grant("1", "po-plan", "Mobile Max", 2))), eq(List.of()));
    }

    @Test
    void anOrderWithNeitherABenefitNorAGrantingPlan_neverCallsTheLedger() throws Exception {
        String body = mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(order("po-plain")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(body).get("id").asString();
        mockMvc.perform(patch(BASE + "/" + orderId).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\": \"completed\"}")).andExpect(status().isOk());
        verifyNoInteractions(choices);
    }

    @Test
    void cancellingAnOrderWithABenefit_releasesWhatItHeld() throws Exception {
        given(choices.hold(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(ChoicesClient.HoldOutcome.held());
        String body = mockMvc.perform(post(BASE).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(order("po-stream")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(body).get("id").asString();
        mockMvc.perform(patch(BASE + "/" + orderId).with(writeToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\": \"cancelled\"}")).andExpect(status().isOk());
        verify(choices).release(orderId);
        verify(choices, never()).settle(anyString(), anyString(), any(), any());
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
