package com.bss.basemigration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With compress-clocks OFF (the production default), the jurisdiction floor
 * is enforced at plan creation: a notice window below one month is not a
 * configuration, it is a violation — the API refuses to store one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "bss.migration.compress-clocks=false")
class NoticeFloorApiTest {

    private static final String BASE = "/tmf-api/baseMigration/v1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noticeDaysBelowTheJurisdictionFloor_isRefused() throws Exception {
        mockMvc.perform(post(BASE + "/migrationPlan")
                        .with(jwt().authorities(new SimpleGrantedAuthority("migration:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Too hasty",
                                 "matrix": [{"sourceOfferingId": "a", "targetOfferingId": "b"}],
                                 "jurisdictionPack": {"noticeDays": 7}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("jurisdiction")));
    }

    @Test
    void theDefaultThirtyDays_standsAtTheFloor() throws Exception {
        mockMvc.perform(post(BASE + "/migrationPlan")
                        .with(jwt().authorities(new SimpleGrantedAuthority("migration:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Lawful pace",
                                 "matrix": [{"sourceOfferingId": "a", "targetOfferingId": "b"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.noticeDays").value(30));
    }
}
