package com.bss.agreement;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgreementTenancyTest {

    private static final String BASE = "/tmf-api/agreementManagement/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("agreement:read"),
                new SimpleGrantedAuthority("agreement:write"));
    }

    @Test
    void agreementsNeverCrossTenants() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/agreement").with(staffOf(ISSUER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"tenant-a terms\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get(BASE + "/agreement/" + id).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/agreement/" + id).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/agreement").with(staffOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
