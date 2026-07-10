package com.bss.stock;

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
 * behave as if the resource does not exist (404, never 403), lists must never
 * leak foreign rows, and stock operations only see the caller's shelf.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockTenancyTest {

    private static final String BASE = "/tmf-api/productStockManagement/v4";
    private static final String ISSUER_DEFAULT = "https://idp.genalpha.test/realms/bss";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("stock:write"));
    }

    private String createStock(String issuer, String offeringId, int amount) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/productStock").with(staffOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Stock %s", "productOffering": {"id": "%s", "name": "Phone"},
                                 "stockedQuantity": {"amount": %d, "units": "unit"}}
                                """.formatted(offeringId, offeringId, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void tenantsCannotSeeEachOthersRows() throws Exception {
        String idA = createStock(ISSUER_A, "po-tenancy-read", 7);

        // The owner reads it back; the other tenant gets 404, not 403.
        mockMvc.perform(get(BASE + "/productStock/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockedQuantity.amount").value(7));
        mockMvc.perform(get(BASE + "/productStock/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Lists and filtered lists never leak foreign rows.
        MvcResult listB = mockMvc.perform(get(BASE + "/productStock?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's stock");
        }
        mockMvc.perform(get(BASE + "/productStock").param("limit", "100")
                        .param("productOfferingId", "po-tenancy-read").with(staffOf(ISSUER_B)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = createStock(ISSUER_A, "po-tenancy-write", 5);

        mockMvc.perform(patch(BASE + "/productStock/" + idA).with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/productStock/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // Still intact and untouched for its owner.
        mockMvc.perform(get(BASE + "/productStock/" + idA).with(staffOf(ISSUER_A)))
                .andExpect(jsonPath("$.name").value("Stock po-tenancy-write"));
    }

    @Test
    void stockOperationsOnlySeeTheCallersShelf() throws Exception {
        createStock(ISSUER_A, "po-tenancy-ops", 3);

        // Another tenant reserving against the same offering id does not touch
        // tenant A's shelf: for tenant B that offering is simply not managed.
        mockMvc.perform(post(BASE + "/reserveProductStock").with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-tenancy-ops"}, "quantity": 3,
                                 "relatedOrder": {"id": "order-b-1"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("notManaged"));

        // Tenant A's availability is untouched by tenant B's attempt.
        mockMvc.perform(get(BASE + "/productStock?productOfferingId=po-tenancy-ops").with(staffOf(ISSUER_A)))
                .andExpect(jsonPath("$[0].availableQuantity.amount").value(3));
    }

    @Test
    void anonymousBrowsingServesTheDefaultTenantOnly() throws Exception {
        String idDefault = createStock(ISSUER_DEFAULT, "po-tenancy-anon-default", 1);
        String idA = createStock(ISSUER_A, "po-tenancy-anon-a", 1);

        mockMvc.perform(get(BASE + "/productStock/" + idDefault))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/productStock/" + idA))
                .andExpect(status().isNotFound());

        MvcResult anonymousList = mockMvc.perform(get(BASE + "/productStock?limit=100"))
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
        MvcResult result = mockMvc.perform(post(BASE + "/productStock")
                        .with(jwt().authorities(new SimpleGrantedAuthority("stock:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"no-issuer stock\", \"stockedQuantity\": {\"amount\": 2}}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get(BASE + "/productStock/" + id))
                .andExpect(status().isOk());
    }
}
