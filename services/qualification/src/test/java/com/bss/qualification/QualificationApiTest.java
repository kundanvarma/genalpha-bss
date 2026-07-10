package com.bss.qualification;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QualificationApiTest {

    private static final String BASE = "/tmf-api/productOfferingQualification/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("qualification:write"));
    }

    private void gateFiber(String prefix) throws Exception {
        mockMvc.perform(post(BASE + "/serviceableArea").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-fiber", "name": "Fiber"},
                                 "postcodePrefix": "%s", "name": "Fiber area %s"}
                                """.formatted(prefix, prefix)))
                .andExpect(status().isCreated());
    }

    @Test
    void ungatedOffering_qualifiesAnywhere_anonymously() throws Exception {
        mockMvc.perform(post(BASE + "/checkProductOfferingQualification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOfferingQualificationItem": [
                                  {"id": "1", "productOffering": {"id": "po-mobile", "name": "Mobile"},
                                   "place": {"postCode": "99999"}}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualificationResult").value("qualified"))
                .andExpect(jsonPath("$.productOfferingQualificationItem[0].qualificationItemResult")
                        .value("qualified"));
    }

    @Test
    void gatedOffering_qualifiesByPostcodePrefix_withReasonWhenNot() throws Exception {
        gateFiber("111");
        gateFiber("222");

        mockMvc.perform(post(BASE + "/checkProductOfferingQualification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOfferingQualificationItem": [
                                  {"id": "1", "productOffering": {"id": "po-fiber", "name": "Fiber"},
                                   "place": {"postCode": "111 22"}},
                                  {"id": "2", "productOffering": {"id": "po-fiber", "name": "Fiber"},
                                   "place": {"postCode": "99999"}}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualificationResult").value("unqualified"))
                .andExpect(jsonPath("$.productOfferingQualificationItem[0].qualificationItemResult")
                        .value("qualified"))
                .andExpect(jsonPath("$.productOfferingQualificationItem[1].qualificationItemResult")
                        .value("unqualified"))
                .andExpect(jsonPath("$.productOfferingQualificationItem[1].eligibilityUnavailabilityReason[0].label")
                        .value("Fiber is not available at postcode 99999"));
    }

    @Test
    void serviceableAreaWrites_requireStaff() throws Exception {
        mockMvc.perform(post(BASE + "/serviceableArea")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productOffering\": {\"id\": \"x\"}, \"postcodePrefix\": \"1\"}"))
                .andExpect(status().isUnauthorized());
    }
}
