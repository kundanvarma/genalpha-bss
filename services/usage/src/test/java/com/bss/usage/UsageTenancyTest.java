package com.bss.usage;

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

/**
 * Pool-model tenancy: usage records, allowance rules and rated charges belong
 * to the tenant of the verified token issuer. Rating and consumption reports
 * never cross tenants — even for the same party id under a different tenant's
 * issuer — and lists never leak foreign rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageTenancyTest {

    private static final String MGMT = "/tmf-api/usageManagement/v4";
    private static final String CONSUMPTION = "/tmf-api/usageConsumption/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor machineOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("usage:read"),
                new SimpleGrantedAuthority("usage:write"));
    }

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("usage:read"));
    }

    private void allowance(String issuer, String offeringId) throws Exception {
        mockMvc.perform(post(MGMT + "/usageAllowance").with(machineOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "%s", "name": "Bundle"},
                                 "usageType": "EU roaming data",
                                 "allowance": {"value": 10, "units": "GB"},
                                 "overagePrice": {"unit": "EUR", "value": 2.50}}
                                """.formatted(offeringId)))
                .andExpect(status().isCreated());
    }

    private void usage(String issuer, String party, String offeringId, double gb) throws Exception {
        mockMvc.perform(post(MGMT + "/usage").with(machineOf(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usageType": "EU roaming data",
                                 "usageCharacteristic": {"value": %s, "units": "GB"},
                                 "productOffering": {"id": "%s"},
                                 "relatedParty": [{"id": "%s", "role": "customer"}]}
                                """.formatted(gb, offeringId, party)))
                .andExpect(status().isCreated());
    }

    private String rateBody(String party) {
        java.time.LocalDate start = java.time.LocalDate.now().withDayOfMonth(1);
        return "{\"relatedPartyId\": \"" + party + "\", \"periodStart\": \"" + start
                + "\", \"periodEnd\": \"" + start.plusMonths(1).minusDays(1) + "\"}";
    }

    @Test
    void ratingAndChargesAreConfinedToTheCallersTenant() throws Exception {
        allowance(ISSUER_A, "po-t1");
        usage(ISSUER_A, "cust-ten-1", "po-t1", 12.3); // 2.3 GB over

        // Tenant B rating the same party sees no records and produces no charges.
        mockMvc.perform(post(MGMT + "/rateUsage").with(machineOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("cust-ten-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(0));

        // The owning tenant still rates the untouched records into a charge.
        mockMvc.perform(post(MGMT + "/rateUsage").with(machineOf(ISSUER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("cust-ten-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount.value").value(5.75));

        // Tenant A's charges stay invisible to tenant B's rating view.
        mockMvc.perform(post(MGMT + "/rateUsage").with(machineOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("cust-ten-1")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void consumptionReportsAndAllowancesNeverCrossTenants() throws Exception {
        allowance(ISSUER_A, "po-t2");
        usage(ISSUER_A, "cust-ten-2", "po-t2", 4.2);

        // The customer sees their buckets inside their own tenant only; the
        // same party id under the other tenant's issuer gets an empty report.
        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption")
                        .with(customerOf(ISSUER_A, "cust-ten-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket[0].usedValue").value(4.2))
                .andExpect(jsonPath("$.bucket[0].allowedValue").value(10));
        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption")
                        .with(customerOf(ISSUER_B, "cust-ten-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket.length()").value(0));

        // Allowances are per-tenant rule data: tenant B lists none of tenant A's.
        MvcResult listB = mockMvc.perform(get(MGMT + "/usageAllowance").with(machineOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains("po-t2")) {
            throw new AssertionError("tenant B allowance list leaked tenant A's rule");
        }
    }
}
