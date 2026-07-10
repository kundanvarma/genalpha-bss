package com.bss.qualification;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: serviceable areas belong to the tenant of the verified
 * token issuer; the anonymous qualification check runs inside the default
 * tenant. Cross-tenant access must behave as if the resource does not exist
 * (404, never 403), and one tenant's gates never constrain another's shop.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QualificationTenancyTest {

    private static final String BASE = "/tmf-api/productOfferingQualification/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("qualification:write"));
    }

    private String gateOffering(String issuer, String offeringId, String prefix) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/serviceableArea").with(staffOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "%s", "name": "Fiber"},
                                 "postcodePrefix": "%s", "name": "Fiber area %s"}
                                """.formatted(offeringId, prefix, prefix)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersRows() throws Exception {
        String idA = gateOffering(ISSUER_A, "po-tenancy-fiber", "111");

        // The owner reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/serviceableArea/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcodePrefix").value("111"));
        mockMvc.perform(get(BASE + "/serviceableArea/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "/serviceableArea?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's serviceable area");
        }
        mockMvc.perform(get(BASE + "/serviceableArea").param("limit", "100")
                        .param("productOfferingId", "po-tenancy-fiber").with(staffOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));

        // Deletes are confined too; the row survives for its owner.
        mockMvc.perform(delete(BASE + "/serviceableArea/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/serviceableArea/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk());
    }

    @Test
    void gatesOnlyConstrainTheirOwnTenantsQualificationCheck() throws Exception {
        gateOffering(ISSUER_A, "po-tenancy-gated", "111");

        String check = """
                {"productOfferingQualificationItem": [
                  {"id": "1", "productOffering": {"id": "po-tenancy-gated", "name": "Fiber"},
                   "place": {"postCode": "99999"}}
                ]}
                """;

        // Inside tenant A the offering is gated and this postcode fails.
        mockMvc.perform(post(BASE + "/checkProductOfferingQualification").with(staffOf(ISSUER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(check))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualificationResult").value("unqualified"));

        // Tenant B has no such gate: the same offering qualifies anywhere.
        mockMvc.perform(post(BASE + "/checkProductOfferingQualification").with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON).content(check))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualificationResult").value("qualified"));

        // Anonymous checks run in the default tenant, also ungated here.
        mockMvc.perform(post(BASE + "/checkProductOfferingQualification")
                        .contentType(MediaType.APPLICATION_JSON).content(check))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualificationResult").value("qualified"));
    }

    @Test
    void tokensWithoutAnIssuerFallBackToTheDefaultTenant() throws Exception {
        // Legacy-style token (no iss claim in mocks): acts inside the default
        // tenant, same as anonymous — existing single-tenant tests keep passing.
        MvcResult result = mockMvc.perform(post(BASE + "/serviceableArea")
                        .with(jwt().authorities(new SimpleGrantedAuthority("qualification:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-tenancy-default", "name": "Fiber"},
                                 "postcodePrefix": "333"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get(BASE + "/serviceableArea/" + id))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/serviceableArea/" + id).with(staffOf(ISSUER_A)))
                .andExpect(status().isNotFound());
    }
}
