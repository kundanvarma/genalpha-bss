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

import static org.hamcrest.Matchers.containsString;
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
 * The plan lifecycle and Choices (#357): a downgrade below what is in use is
 * refused with the benefits named (A5); a downgrade that fits re-grants; a
 * cease ends the newest benefits it can no longer pay for, in the same order,
 * never a held one silently; a held benefit cannot be released before its date.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderPlanLifecycleTest {

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

    private static final List<Map<String, Object>> PLAN_LINEAGE =
            List.of(Map.of("productOrderId", "ord-plan", "orderItemId", "1"));

    @BeforeEach
    void stubs() {
        given(catalogClient.findOffering(anyString()))
                .willAnswer(i -> Optional.of(new CatalogClient.OfferingRef(i.getArgument(0), i.getArgument(0))));
        given(catalogClient.findOfferingDetail("po-big")).willReturn(Optional.of(Map.of("id", "po-big",
                "name", "Mobile Unlimited", "lifecycleStatus", "Launched", "choicesGranted", 2)));
        given(catalogClient.findOfferingDetail("po-mid")).willReturn(Optional.of(Map.of("id", "po-mid",
                "name", "Mobile 60 GB", "lifecycleStatus", "Launched", "choicesGranted", 1)));
        given(catalogClient.findOfferingDetail("po-stream")).willReturn(Optional.of(Map.of("id", "po-stream",
                "name", "StreamCo Standard", "lifecycleStatus", "Launched", "choiceCost", 1)));
        given(inventoryClient.getProduct("prod-plan")).willReturn(Optional.of(Map.of("id", "prod-plan",
                "name", "Mobile Unlimited", "status", "active",
                "productOffering", Map.of("id", "po-big", "name", "Mobile Unlimited"),
                "relatedParty", List.of(Map.of("id", "cust-1", "role", "customer")),
                "productOrderItem", PLAN_LINEAGE)));
        given(inventoryClient.getProduct("prod-stream")).willReturn(Optional.of(Map.of("id", "prod-stream",
                "name", "StreamCo Standard", "status", "active",
                "productOffering", Map.of("id", "po-stream", "name", "StreamCo Standard"),
                "relatedParty", List.of(Map.of("id", "cust-1", "role", "customer")),
                "productOrderItem", List.of(Map.of("productOrderId", "ord-s", "orderItemId", "1")))));
    }

    private void poolWith(String streamHeldUntil, ChoicesClient.InUse... more) {
        java.util.List<ChoicesClient.InUse> inUse = new java.util.ArrayList<>(List.of(
                new ChoicesClient.InUse("StreamCo Standard", 1, "committed", "ord-s", "1", streamHeldUntil)));
        inUse.addAll(List.of(more));
        given(choices.pool("cust-1")).willReturn(new ChoicesClient.Pool(2, inUse.size(),
                List.of(new ChoicesClient.GrantView("Mobile Unlimited", 2, "ord-plan", "1")), inUse, 0, "2027-01-01"));
    }

    private String body(String items) {
        return "{\"relatedParty\": [{\"id\": \"cust-1\", \"role\": \"customer\"}], \"productOrderItem\": [" + items + "]}";
    }

    private static final String DOWNGRADE = "{\"id\": \"1\", \"action\": \"modify\", \"product\": {\"id\": \"prod-plan\"},"
            + " \"productOffering\": {\"id\": \"po-mid\", \"name\": \"Mobile 60 GB\"}}";

    @Test
    void aDowngradeBelowWhatIsInUse_isRefused_withTheBenefitsNamed() throws Exception {
        poolWith(null, new ChoicesClient.InUse("Lydbok+ Audiobooks", 1, "committed", "ord-l", "1", null));
        mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON).content(body(DOWNGRADE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("This plan gives 1 Choice and 2 are in use")))
                .andExpect(jsonPath("$.message").value(containsString("StreamCo Standard, Lydbok+ Audiobooks")));
        verify(inventoryClient, never()).updateProduct(anyString(), any());
    }

    @Test
    void aDowngradeThatFits_regrantsTheNewPlansChoices() throws Exception {
        poolWith(null);   // 1 in use; the new plan gives 1
        mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON).content(body(DOWNGRADE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("completed"));
        verify(choices).settle(anyString(), eq("cust-1"), eq(List.of()), eq(List.of()),
                eq(List.of(new ChoicesClient.Regrant("ord-plan", "1", "po-mid", "Mobile 60 GB", 1))));
    }

    @Test
    void aCease_endsTheBenefitsItCanNoLongerPayFor_inTheSameOrder() throws Exception {
        poolWith(null);
        given(inventoryClient.activeProductsOf("cust-1")).willReturn(List.of(Map.of("id", "prod-stream",
                "name", "StreamCo Standard", "status", "active",
                "productOffering", Map.of("id", "po-stream", "name", "StreamCo Standard"),
                "productOrderItem", List.of(Map.of("productOrderId", "ord-s", "orderItemId", "1")))));
        mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"id\": \"1\", \"action\": \"delete\", \"product\": {\"id\": \"prod-plan\"}}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productOrderItem[1].action").value("delete"))
                .andExpect(jsonPath("$.productOrderItem[1].product.name").value("StreamCo Standard"));
        verify(inventoryClient).updateProduct(eq("prod-stream"), any());
        verify(choices).settle(anyString(), eq("cust-1"), eq(List.of()),
                eq(List.of(new ChoicesClient.Ending("ord-s", "1", false))),
                eq(List.of(new ChoicesClient.Regrant("ord-plan", "1", null, null, 0))));
    }

    @Test
    void aCeaseThatWouldEndAHeldBenefit_isRefused_withItsDate() throws Exception {
        poolWith("2099-01-01");
        given(inventoryClient.activeProductsOf("cust-1")).willReturn(List.of());
        mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"id\": \"1\", \"action\": \"delete\", \"product\": {\"id\": \"prod-plan\"}}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("StreamCo Standard (until 2099-01-01)")));
    }

    @Test
    void aHeldBenefit_cannotBeReleasedBeforeItsDate() throws Exception {
        poolWith("2099-01-01");
        mockMvc.perform(post(BASE).with(token()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"id\": \"1\", \"action\": \"delete\", \"product\": {\"id\": \"prod-stream\"}}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("StreamCo Standard holds its Choice until 2099-01-01 — it can be released or swapped from then"));
        verify(inventoryClient, never()).updateProduct(anyString(), any());
    }

    private static RequestPostProcessor token() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"), new SimpleGrantedAuthority("ordering:read"));
    }
}
