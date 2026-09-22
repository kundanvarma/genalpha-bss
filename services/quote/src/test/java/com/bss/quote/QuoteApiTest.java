package com.bss.quote;

import com.bss.quote.client.DownstreamClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lead-to-order: intent proposal in, priced quote out, acceptance places the order. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuoteApiTest {

    private static final String BASE = "/tmf-api/quoteManagement/v4";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DownstreamClients downstream;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("quote:read"),
                new SimpleGrantedAuthority("quote:write"));
    }

    /** The foreign documents the client hands back are trees, as on the wire. */
    private static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }

    private void mockProposal() {
        when(downstream.intent("intent-1")).thenReturn(tree(Map.of(
                "id", "intent-1", "name", "Tournament slice",
                "relatedParty", List.of(Map.of("id", "stadium-org", "role", "customer")),
                "intentReport", Map.of("feasible", true, "proposedItems", List.of(
                        Map.of("offeringName", "Stadium 5G Slice", "reason", "2000 Mbps at 8ms"),
                        Map.of("offeringName", "Edge AI Inferencing", "reason", "GPU next door"))))));
        // only sellable lifecycles are quotable — a retired offering fails fast here
        when(downstream.offerings()).thenReturn(tree(List.of(
                Map.of("id", "off-slice", "name", "Stadium 5G Slice", "lifecycleStatus", "Active",
                        "productOfferingPrice", List.of(Map.of("id", "price-slice"))),
                Map.of("id", "off-ai", "name", "Edge AI Inferencing", "lifecycleStatus", "Launched",
                        "productOfferingPrice", List.of(Map.of("id", "price-ai"))))));
        when(downstream.offeringPrice("price-slice")).thenReturn(tree(Map.of(
                "price", Map.of("value", 4900, "unit", "EUR"), "recurringChargePeriodType", "month")));
        when(downstream.offeringPrice("price-ai")).thenReturn(tree(Map.of(
                "price", Map.of("value", 990, "unit", "EUR"), "recurringChargePeriodType", "month")));
        when(downstream.allowances()).thenReturn(tree(List.of(Map.of(
                "productOffering", Map.of("id", "off-ai"),
                "usageType", "AI inference tokens",
                "allowance", Map.of("value", 50, "units", "Mtokens"),
                "overagePrice", Map.of("unit", "EUR", "value", 4.0)))));
        when(downstream.quoteNarrative(any())).thenReturn("Connectivity plus metered edge AI.");
    }

    @Test
    void quoteLifecycle_intentToOrder() throws Exception {
        mockProposal();
        when(downstream.placeOrder(any())).thenReturn(tree(Map.of("id", "po-777", "state", "acknowledged")));
        when(downstream.createAgreement(any())).thenReturn(tree(Map.of("id", "agr-1")));

        String id = mockMvc.perform(post(BASE + "/quote").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intentId\": \"intent-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("inProgress"))
                .andExpect(jsonPath("$.quoteItem", hasSize(2)))
                .andExpect(jsonPath("$.quoteItem[0].offering.name").value("Stadium 5G Slice"))
                .andExpect(jsonPath("$.quoteItem[0].unitPrice.period").value("month"))
                .andExpect(jsonPath("$.quoteItem[1].allowance.usageType").value("AI inference tokens"))
                .andExpect(jsonPath("$.quoteItem[1].allowance.included.units").value("Mtokens"))
                .andExpect(jsonPath("$.quoteTotalPrice.value").value(5890))
                .andExpect(jsonPath("$.relatedParty[0].id").value("stadium-org"))
                .andExpect(jsonPath("$.narrative").value(containsString("metered")))
                .andExpect(jsonPath("$.@type").value("Quote"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("^\\{\"id\":\"([^\"]+)\".*$", "$1");

        // acceptance before approval is refused
        mockMvc.perform(post(BASE + "/quote/" + id + "/accept").with(staff()))
                .andExpect(status().isConflict());

        mockMvc.perform(patch(BASE + "/quote/" + id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approved"));

        mockMvc.perform(post(BASE + "/quote/" + id + "/accept").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("accepted"))
                .andExpect(jsonPath("$.productOrder.id").value("po-777"))
                .andExpect(jsonPath("$.agreement.id").value("agr-1"));

        // the stored lines read back as they were written
        mockMvc.perform(get(BASE + "/quote/" + id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteItem", hasSize(2)))
                .andExpect(jsonPath("$.quoteItem[1].allowance.overagePrice.value").value(4.0));
    }

    @Test
    void infeasibleIntentsCannotBeQuoted() throws Exception {
        when(downstream.intent(anyString())).thenReturn(tree(Map.of(
                "id", "intent-2", "intentReport", Map.of("feasible", false))));
        mockMvc.perform(post(BASE + "/quote").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intentId\": \"intent-2\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aRequestBodyIsARecord_unknownFieldsAreDropped() throws Exception {
        // a body cannot smuggle a field the record does not declare (mass assignment)
        mockMvc.perform(post(BASE + "/quote/configRule").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"requires\",\"subjectOffering\":\"IP\",\"objectOffering\":\"Line\","
                                + "\"tenantId\":\"someone-else\",\"id\":\"forged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleType").value("requires"))
                .andExpect(jsonPath("$.message").value("IP requires Line"));
        mockMvc.perform(post(BASE + "/quote/validate").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"offeringName\":\"IP\",\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.violations[0].subject").value("IP"))
                .andExpect(jsonPath("$.violations[0].object").value("Line"));
    }

    @Test
    void quotesAreBackOffice() throws Exception {
        mockMvc.perform(get(BASE + "/quote")).andExpect(status().isUnauthorized());
    }
}
