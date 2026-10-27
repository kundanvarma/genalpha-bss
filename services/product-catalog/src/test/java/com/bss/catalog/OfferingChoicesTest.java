package com.bss.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Benefit Choices on the offering (#354): a plan's {@code choicesGranted} and a
 * benefit's {@code choiceCost} ride beside TMF620, round-trip, and are counts —
 * 1 or more, or absent — never zero or negative.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfferingChoicesTest {

    private static final String BASE = "/tmf-api/productCatalogManagement/v4/productOffering";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aBenefitsCost_andAPlansGrant_roundTrip() throws Exception {
        mockMvc.perform(post(BASE).with(jwt().authorities(new SimpleGrantedAuthority("catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"StreamCo Standard\", \"lifecycleStatus\": \"Active\", \"choiceCost\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.choiceCost").value(1))
                .andExpect(jsonPath("$.choicesGranted").doesNotExist());
        String plan = mockMvc.perform(post(BASE).with(jwt().authorities(new SimpleGrantedAuthority("catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Mobile Max\", \"lifecycleStatus\": \"Active\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(plan).get("id").asString();
        mockMvc.perform(patch(BASE + "/" + id).with(jwt().authorities(new SimpleGrantedAuthority("catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choicesGranted\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesGranted").value(2));
    }

    @Test
    void aZeroOrNegativeCount_isRefused() throws Exception {
        mockMvc.perform(post(BASE).with(jwt().authorities(new SimpleGrantedAuthority("catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Free lunch\", \"choiceCost\": 0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).with(jwt().authorities(new SimpleGrantedAuthority("catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Debt plan\", \"choicesGranted\": -1}"))
                .andExpect(status().isBadRequest());
    }
}
