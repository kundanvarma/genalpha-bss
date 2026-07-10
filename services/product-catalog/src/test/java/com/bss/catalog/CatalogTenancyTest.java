package com.bss.catalog;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: rows belong to the tenant of the verified token issuer;
 * anonymous browsing belongs to the default tenant. Cross-tenant access must
 * behave as if the resource does not exist (404, never 403), and lists must
 * never leak foreign rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogTenancyTest {

    private static final String BASE = "/tmf-api/productCatalogManagement/v4";
    private static final String ISSUER_DEFAULT = "https://idp.genalpha.test/realms/bss";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("catalog:read"),
                new SimpleGrantedAuthority("catalog:write"));
    }

    private String createOffering(String issuer, String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/productOffering").with(staffOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersRows() throws Exception {
        String idA = createOffering(ISSUER_A, "Tenant-A only plan");

        // The owner reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/productOffering/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tenant-A only plan"));
        mockMvc.perform(get(BASE + "/productOffering/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "/productOffering?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's offering");
        }
        mockMvc.perform(get(BASE + "/productOffering").param("limit", "100")
                        .param("name", "Tenant-A only plan").with(staffOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = createOffering(ISSUER_A, "Tenant-A patch target");

        mockMvc.perform(patch(BASE + "/productOffering/" + idA).with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/productOffering/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Still intact and untouched for its owner.
        mockMvc.perform(get(BASE + "/productOffering/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(jsonPath("$.name").value("Tenant-A patch target"));
    }

    @Test
    void anonymousBrowsingServesTheDefaultTenantOnly() throws Exception {
        String idDefault = createOffering(ISSUER_DEFAULT, "Public genalpha plan");
        String idA = createOffering(ISSUER_A, "Tenant-A private plan");

        mockMvc.perform(get(BASE + "/productOffering/" + idDefault))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/productOffering/" + idA))
                .andExpect(status().isNotFound());

        MvcResult anonymousList = mockMvc.perform(get(BASE + "/productOffering?limit=100"))
                .andExpect(status().isOk()).andReturn();
        String body = anonymousList.getResponse().getContentAsString();
        if (!body.contains(idDefault) || body.contains(idA)) {
            throw new AssertionError("anonymous list not confined to the default tenant");
        }
    }

    @Test
    void tokensWithoutAnIssuerFallBackToTheDefaultTenant() throws Exception {
        // Legacy-style token (no iss claim in mocks): acts inside the default
        // tenant, same as anonymous — existing single-tenant tests keep passing.
        MvcResult result = mockMvc.perform(post(BASE + "/productOffering")
                        .with(jwt().authorities(new SimpleGrantedAuthority("catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"No-issuer offering\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get(BASE + "/productOffering/" + id))
                .andExpect(status().isOk());
    }
}
