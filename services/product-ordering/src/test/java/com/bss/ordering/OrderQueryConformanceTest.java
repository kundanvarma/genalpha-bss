package com.bss.ordering;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TMF630/TMF622 conformance behaviors exercised by the TM Forum CTK: server-set
 * default state, mandatory productOrderItem, attribute filtering, and
 * fields-based attribute selection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderQueryConformanceTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private AgreementClient agreementClient;

    @MockBean
    private PartyClient partyClient;

    @MockBean
    private InventoryClient inventoryClient;

    @Test
    void create_withoutState_defaultsToAcknowledged_andEchoesItems() throws Exception {
        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "42", "action": "add"}],
                                 "relatedParty": [{"id": "p-1", "@referredType": "Individual"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("acknowledged"))
                .andExpect(jsonPath("$.orderDate").exists())
                .andExpect(jsonPath("$.productOrderItem[0].id").value("42"))
                .andExpect(jsonPath("$.productOrderItem[0].action").value("add"))
                .andExpect(jsonPath("$.relatedParty[0].['@referredType']").value("Individual"));
    }

    @Test
    void create_withoutProductOrderItem_isRejected() throws Exception {
        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "no items"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void filteringById_returnsExactlyThatOrder() throws Exception {
        String id = createOrder();
        createOrder();

        MvcResult result = mockMvc.perform(get(BASE).with(readToken())
                        .queryParam("id", id)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(id);
        assertThat(result.getResponse().getHeader("X-Total-Count")).isEqualTo("1");
    }

    @Test
    void filteringByOrderDate_matchesTheCreatedInstance() throws Exception {
        String id = createOrder();
        String orderDate = objectMapper.readTree(mockMvc.perform(get(BASE + "/" + id).with(readToken()))
                        .andReturn().getResponse().getContentAsString())
                .get("orderDate").asText();

        MvcResult result = mockMvc.perform(get(BASE).with(readToken())
                        .queryParam("orderDate", orderDate)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(list).isNotEmpty();
        List<String> ids = list.findValuesAsText("id");
        assertThat(ids).contains(id);
    }

    @Test
    void unknownFilterAttribute_isRejected() throws Exception {
        mockMvc.perform(get(BASE).with(readToken())
                        .queryParam("bogusAttribute", "x")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void fieldsSelection_returnsOnlyRequestedFieldsPlusId() throws Exception {
        createOrder();
        MvcResult result = mockMvc.perform(get(BASE).with(readToken())
                        .queryParam("fields", "href,orderDate")
                        .queryParam("limit", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(list).isNotEmpty();
        JsonNode first = list.get(0);
        assertThat(first.has("href")).isTrue();
        assertThat(first.has("orderDate")).isTrue();
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("state")).isFalse();
        assertThat(first.has("productOrderItem")).isFalse();
    }

    private String createOrder() throws Exception {
        String response = mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}]}
                                """))
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
