package com.bss.som;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The AI-slice intent loop: physics-based feasibility + the network's upsell. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntentApiTest {

    private static final String INTENT = "/tmf-api/intentManagement/v4/intent";
    private static final String POOLS = "/tmf-api/resourcePoolManagement/v4/resourcePool";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("service:read"),
                new SimpleGrantedAuthority("service:write"));
    }

    private void mintEdgePool(String name) throws Exception {
        mockMvc.perform(post(POOLS).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "resourceType": "edge-gpu", "prefix": "gpu-%s-"}
                                """.formatted(name, name)))
                .andExpect(status().isCreated());
    }

    @Test
    void lowLatencyIntentWithoutEdgeIsInfeasible_physicsNotPolicy() throws Exception {
        mockMvc.perform(post(INTENT).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Nowhere arena slice",
                                 "expression": {"place": "nowhere-arena", "latencyMs": 8, "bandwidthMbps": 2000}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("infeasible"))
                .andExpect(jsonPath("$.intentReport.feasible").value(false))
                .andExpect(jsonPath("$.intentReport.reason").value(containsString("physics")));
    }

    @Test
    void edgeCoverageMakesItFeasible_andTheNetworkUpsellsAi() throws Exception {
        mintEdgePool("stadium-testville");

        // The customer only asked for connectivity — the OSS proposes AI too.
        mockMvc.perform(post(INTENT).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Testville tournament slice",
                                 "relatedParty": [{"id": "stadium-org-1", "role": "customer"}],
                                 "expression": {"place": "stadium-testville", "latencyMs": 8, "bandwidthMbps": 2000}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("feasibilityChecked"))
                .andExpect(jsonPath("$.intentReport.feasible").value(true))
                .andExpect(jsonPath("$.intentReport.deliveryPoint").value(containsString("edge:")))
                .andExpect(jsonPath("$.intentReport.proposedItems", hasSize(2)))
                .andExpect(jsonPath("$.intentReport.proposedItems[1].service").value("edge-ai-inferencing"))
                .andExpect(jsonPath("$.intentReport.expectation.slaBacked").value(true));
    }

    @Test
    void relaxedLatencyIsServedFromRegionalCloud() throws Exception {
        mockMvc.perform(post(INTENT).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Office branch connectivity",
                                 "expression": {"place": "office-park", "latencyMs": 50, "bandwidthMbps": 500}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("feasibilityChecked"))
                .andExpect(jsonPath("$.intentReport.deliveryPoint").value("regional-cloud"))
                .andExpect(jsonPath("$.intentReport.proposedItems", hasSize(1)));
    }

    @Test
    void intentsAreStaffOnly() throws Exception {
        mockMvc.perform(post(INTENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
