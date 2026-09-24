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

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The R18 task faces take a whole TMF document. The envelope is typed and the
 * fields the server owns cannot ride in on it: a body that names an id, a
 * state or a tenant gets the server's own, and its extension fields survive
 * — that is the contract the kits assert.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyEnvelopeTest {

    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("qualification:write"));
    }

    @Test
    void poqDocument_cannotCarryServerOwnedFields_butKeepsItsExtensions() throws Exception {
        mockMvc.perform(post("/tmf-api/productOfferingQualification/v4/productOfferingQualification").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "evil", "href": "/nowhere", "state": "acknowledged", "tenantId": "other-tenant",
                                 "@type": "ProductOfferingQualification", "operatorNote": "kept",
                                 "productOfferingQualificationItem": [{"productOffering": {"id": "po-x"}}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_RE)))
                .andExpect(jsonPath("$.id").value(not("evil")))
                .andExpect(jsonPath("$.state").value("done"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.['@type']").value("ProductOfferingQualification"))
                .andExpect(jsonPath("$.operatorNote").value("kept"))
                .andExpect(jsonPath("$.productOfferingQualificationItem[0].state").value("done"));
    }

    @Test
    void serviceQualificationDocument_cannotCarryServerOwnedFields_butKeepsItsExtensions() throws Exception {
        mockMvc.perform(post("/tmf-api/serviceQualificationManagement/v3/serviceQualification").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "evil", "state": "acknowledged", "tenantId": "other-tenant",
                                 "externalId": "ext-1", "@baseType": "ServiceQualification", "operatorNote": "kept",
                                 "serviceQualificationItem": [{"service": {"serviceType": "fibre"}}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_RE)))
                .andExpect(jsonPath("$.state").value("done"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.externalId").value("ext-1"))
                .andExpect(jsonPath("$.['@baseType']").value("ServiceQualification"))
                .andExpect(jsonPath("$.operatorNote").value("kept"));
    }

    @Test
    void poqDocument_withoutItems_isRefused() throws Exception {
        mockMvc.perform(post("/tmf-api/productOfferingQualification/v4/productOfferingQualification").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"nothing to qualify\"}"))
                .andExpect(status().isBadRequest());
    }
}
