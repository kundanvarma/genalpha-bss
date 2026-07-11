package com.bss.address;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeographicAddressApiTest {

    private static final String BASE = "/tmf-api/geographicAddressManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validation_isAnonymous_normalizes_andJudges() throws Exception {
        mockMvc.perform(post(BASE + "/geographicAddressValidation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submittedGeographicAddress": {"street1": " Storgatan 1 ",
                                 "postCode": "111 22", "city": "sTOCKHOLM", "country": "se"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validationResult").value("success"))
                .andExpect(jsonPath("$.standardizedGeographicAddress.postCode").value("11122"))
                .andExpect(jsonPath("$.standardizedGeographicAddress.city").value("Stockholm"))
                .andExpect(jsonPath("$.standardizedGeographicAddress.country").value("SE"));

        mockMvc.perform(post(BASE + "/geographicAddressValidation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submittedGeographicAddress": {"street1": "x",
                                 "postCode": "1234", "city": "Stockholm", "country": "SE"}}
                                """))
                .andExpect(jsonPath("$.validationResult").value("failed"))
                .andExpect(jsonPath("$.validationReason").value(
                        org.hamcrest.Matchers.containsString("not a valid SE postcode")));

        mockMvc.perform(post(BASE + "/geographicAddressValidation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submittedGeographicAddress\": {\"street1\": \"x\", \"postCode\": \"11\", \"city\": \"y\", \"country\": \"XX\"}}"))
                .andExpect(jsonPath("$.validationReason").value(
                        org.hamcrest.Matchers.containsString("not served")));
    }

    @Test
    void storedAddresses_requireIdentity_andAreStandardized() throws Exception {
        mockMvc.perform(post(BASE + "/geographicAddress")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(BASE + "/geographicAddress")
                        .with(jwt().authorities(new SimpleGrantedAuthority("address:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"street1": "Kungsgatan 2", "postCode": "222 33",
                                 "city": "göteborg", "country": "se"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value("Göteborg"))
                .andExpect(jsonPath("$.postCode").value("22233"));

        mockMvc.perform(get(BASE + "/geographicAddress").param("city", "Göteborg")
                        .with(jwt().authorities(new SimpleGrantedAuthority("address:read"))))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
