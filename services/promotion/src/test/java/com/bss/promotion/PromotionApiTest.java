package com.bss.promotion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PromotionApiTest {

    private static final String BASE = "/tmf-api/promotionManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("promotion:read"),
                new SimpleGrantedAuthority("promotion:write"));
    }

    private void mintPromo(String code, double pct) throws Exception {
        mockMvc.perform(post(BASE + "/promotion").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Welcome offer", "code": "%s", "percentage": %s,
                                 "durationMonths": 6, "appliesTo": ["po-bundle"]}
                                """.formatted(code, pct)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lifecycleStatus").value("Active"));
    }

    @Test
    void anonymousValidation_neverEnumerates() throws Exception {
        mintPromo("WELCOME10", 10);

        // The shop window can check a code with no identity at all.
        mockMvc.perform(post(BASE + "/checkPromotion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"welcome10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.percentage").value(10))
                .andExpect(jsonPath("$.appliesTo[0]").value("po-bundle"));
        mockMvc.perform(post(BASE + "/checkPromotion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"NOPE\"}"))
                .andExpect(jsonPath("$.valid").value(false));

        // But listing promotions requires back-office identity.
        mockMvc.perform(get(BASE + "/promotion"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redemption_isOncePerCustomer_andReadableForBilling() throws Exception {
        mintPromo("SPRING20", 20);

        mockMvc.perform(post(BASE + "/redemption").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"SPRING20\", \"relatedPartyId\": \"cust-p1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percentage").value(20))
                .andExpect(jsonPath("$.monthsLeft").value(6));
        mockMvc.perform(post(BASE + "/redemption").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"SPRING20\", \"relatedPartyId\": \"cust-p1\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get(BASE + "/redemption").param("relatedPartyId", "cust-p1").with(staff()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Welcome offer"));
        mockMvc.perform(get(BASE + "/redemption").param("relatedPartyId", "cust-clean").with(staff()))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
