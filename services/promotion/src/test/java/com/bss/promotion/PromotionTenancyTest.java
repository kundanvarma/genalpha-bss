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
class PromotionTenancyTest {

    private static final String BASE = "/tmf-api/promotionManagement/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("promotion:read"),
                new SimpleGrantedAuthority("promotion:write"));
    }

    @Test
    void promoCodesAreTenantLocal() throws Exception {
        mockMvc.perform(post(BASE + "/promotion").with(staffOf(ISSUER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"A only\", \"code\": \"TENANTA\", \"percentage\": 15}"))
                .andExpect(status().isCreated());

        // The other tenant neither validates nor lists it — and may reuse the code.
        mockMvc.perform(post(BASE + "/checkPromotion").with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"TENANTA\"}"))
                .andExpect(jsonPath("$.valid").value(false));
        mockMvc.perform(get(BASE + "/promotion").with(staffOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post(BASE + "/promotion").with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"B reuse\", \"code\": \"TENANTA\", \"percentage\": 5}"))
                .andExpect(status().isCreated());
    }
}
