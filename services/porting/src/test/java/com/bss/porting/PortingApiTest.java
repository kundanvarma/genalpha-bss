package com.bss.porting;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Port-in through the country clearinghouse: validate, schedule, cut over. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortingApiTest {

    private static final String BASE = "/tmf-api/numberPortingManagement/v1";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("porting:read"),
                new SimpleGrantedAuthority("porting:write"));
    }

    @Test
    void portIn_validatesSchedulesAndCompletes_thenNumberIsAvailableToActivation() throws Exception {
        String id = mockMvc.perform(post(BASE + "/numberPortingOrder").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "portIn", "phoneNumber": "+4790112233", "country": "NO",
                                 "otherOperator": "OtherTelco",
                                 "relatedParty": [{"id": "port-party-1", "role": "customer"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("scheduled"))
                .andExpect(jsonPath("$.regulator").value(containsString("Nkom")))
                .andExpect(jsonPath("$.scheduledCutover").isNotEmpty())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("^\\{\"id\":\"([^\"]+)\".*$", "$1");

        // The number is not the party's until the cutover completes.
        mockMvc.perform(get(BASE + "/portedNumber?relatedPartyId=port-party-1").with(staff()))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist());

        mockMvc.perform(post(BASE + "/numberPortingOrder/" + id + "/complete").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));

        // Now the orchestrator can activate on the ported number.
        mockMvc.perform(get(BASE + "/portedNumber?relatedPartyId=port-party-1").with(staff()))
                .andExpect(jsonPath("$.phoneNumber").value("+4790112233"));
    }

    @Test
    void badNumberFormat_isRejectedByTheClearinghouse() throws Exception {
        mockMvc.perform(post(BASE + "/numberPortingOrder").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "portIn", "phoneNumber": "12345", "country": "NO",
                                 "otherOperator": "OtherTelco"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectReason").isNotEmpty());
    }

    @Test
    void disputedNumber_isRejected_andCannotComplete() throws Exception {
        String id = mockMvc.perform(post(BASE + "/numberPortingOrder").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "portIn", "phoneNumber": "+4790110000", "country": "NO",
                                 "otherOperator": "OtherTelco"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("^\\{\"id\":\"([^\"]+)\".*$", "$1");

        mockMvc.perform(post(BASE + "/numberPortingOrder/" + id + "/complete").with(staff()))
                .andExpect(status().isConflict());
    }

    @Test
    void portingIsStaffOnly() throws Exception {
        mockMvc.perform(post(BASE + "/numberPortingOrder")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
