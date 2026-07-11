package com.bss.intelligence;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.bss.intelligence.client.BssApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChurnLearningApiTest {

    private static final String BASE = "/ai/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @MockBean
    private BssApiClient bss;

    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    private static RequestPostProcessor staff() {
        return jwt().jwt(j -> j.issuer(ISSUER_A)).authorities(new SimpleGrantedAuthority("ai:use"));
    }

    private static RequestPostProcessor untouchedTenantStaff() {
        return jwt().jwt(j -> j.issuer(ISSUER_B)).authorities(new SimpleGrantedAuthority("ai:use"));
    }

    @Test
    @Order(1)
    void untrainedModelSaysSoAndRefusesThinData() throws Exception {
        mockMvc.perform(get(BASE + "/churnModel").with(untouchedTenantStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trained").value(false));

        mockMvc.perform(post(BASE + "/churnTraining/import").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\": [{\"features\": [10, 0.5, 3, 1], \"churned\": true}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(2)
    void importsHistoryTrainsAndReportsTheModel() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            boolean churner = i % 2 == 0;
            rows.add(Map.of(
                    "features", List.of(churner ? 15 + i % 20 : 200 + i, 0.5,
                            churner ? 3 : 0, churner ? 1 : 0),
                    "churned", churner));
        }
        mockMvc.perform(post(BASE + "/churnTraining/import").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rows", rows))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trained").value(true))
                .andExpect(jsonPath("$.sampleCount").value(60))
                .andExpect(jsonPath("$.source").value("imported-history"));

        mockMvc.perform(get(BASE + "/churnModel").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trained").value(true))
                .andExpect(jsonPath("$.sampleCount").value(60));
    }

    @Test
    @Order(3)
    void outcomesAreRecordedForFutureTraining() throws Exception {
        mockMvc.perform(post(BASE + "/churnOutcome").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"party\": {\"id\": \"learn-party-1\"}, \"churned\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.churned").value(true));

        mockMvc.perform(get(BASE + "/churnModel").with(staff()))
                .andExpect(jsonPath("$.labeledOutcomes").value(1));
    }
}
