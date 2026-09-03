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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Geography is the tenant's, not the platform's: an operator that lists its
 * served countries gets a generic postcode rule for the ones the platform has
 * no specific rule for; an operator that lists nothing keeps the built-in set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServedCountriesTest {

    private static final String BASE = "/tmf-api/geographicAddressManagement/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static String body(String country, String postCode) {
        return "{\"submittedGeographicAddress\": {\"street1\": \"12 Camp Street\", \"postCode\": \"%s\", \"city\": \"Georgetown\", \"country\": \"%s\"}}"
                .formatted(postCode, country);
    }

    @Test
    void aTenantSellingIntoGuyanaAcceptsGuyanaAndNothingElse() throws Exception {
        var tenantA = jwt().jwt(j -> j.issuer(ISSUER_A)).authorities(new SimpleGrantedAuthority("address:read"));
        mockMvc.perform(post(BASE + "/geographicAddressValidation").with(tenantA)
                        .contentType(MediaType.APPLICATION_JSON).content(body("GY", "4131519")))
                .andExpect(jsonPath("$.validationResult").value("success"))
                .andExpect(jsonPath("$.standardizedGeographicAddress.country").value("GY"));
        mockMvc.perform(post(BASE + "/geographicAddressValidation").with(tenantA)
                        .contentType(MediaType.APPLICATION_JSON).content(body("gy", "413 1519")))
                .andExpect(jsonPath("$.validationResult").value("success"));
        mockMvc.perform(post(BASE + "/geographicAddressValidation").with(tenantA)
                        .contentType(MediaType.APPLICATION_JSON).content(body("SE", "11122")))
                .andExpect(jsonPath("$.validationReason").value(org.hamcrest.Matchers.containsString("'SE' is not served")));
        mockMvc.perform(post(BASE + "/geographicAddressValidation").with(tenantA)
                        .contentType(MediaType.APPLICATION_JSON).content(body("GY", "41315")))
                .andExpect(jsonPath("$.validationReason").value(org.hamcrest.Matchers.containsString("not a valid GY postcode")));
    }

    @Test
    void theDefaultTenantKeepsThePlatformRuleSet() throws Exception {
        mockMvc.perform(post(BASE + "/geographicAddressValidation")
                        .contentType(MediaType.APPLICATION_JSON).content(body("GY", "4131519")))
                .andExpect(jsonPath("$.validationReason").value(org.hamcrest.Matchers.containsString("'GY' is not served")));
        mockMvc.perform(post(BASE + "/geographicAddressValidation")
                        .contentType(MediaType.APPLICATION_JSON).content(body("NO", "0150")))
                .andExpect(jsonPath("$.validationResult").value("success"));
    }
}
