package com.bss.usage;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageApiTest {

    private static final String MGMT = "/tmf-api/usageManagement/v4";
    private static final String CONSUMPTION = "/tmf-api/usageConsumption/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor machine() {
        return jwt().authorities(
                new SimpleGrantedAuthority("usage:read"),
                new SimpleGrantedAuthority("usage:write"));
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("usage:read"));
    }

    private void allowance(String offeringId, double allowed, double pricePerUnit) throws Exception {
        mockMvc.perform(post(MGMT + "/usageAllowance").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "%s", "name": "Bundle"},
                                 "usageType": "EU roaming data",
                                 "allowance": {"value": %s, "units": "GB"},
                                 "overagePrice": {"unit": "EUR", "value": %s}}
                                """.formatted(offeringId, allowed, pricePerUnit)))
                .andExpect(status().isCreated());
    }

    private void usage(String party, String offeringId, double gb) throws Exception {
        mockMvc.perform(post(MGMT + "/usage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usageType": "EU roaming data",
                                 "usageCharacteristic": {"value": %s, "units": "GB"},
                                 "productOffering": {"id": "%s"},
                                 "relatedParty": [{"id": "%s", "role": "customer"}]}
                                """.formatted(gb, offeringId, party)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("received"));
    }

    private String rateBody(String party) {
        java.time.LocalDate start = java.time.LocalDate.now().withDayOfMonth(1);
        return "{\"relatedPartyId\": \"" + party + "\", \"periodStart\": \"" + start
                + "\", \"periodEnd\": \"" + start.plusMonths(1).minusDays(1) + "\"}";
    }

    @Test
    void overageIsRated_underAllowanceIsNot_andRatingIsIdempotent() throws Exception {
        allowance("po-u1", 10, 2.50);
        usage("cust-u1", "po-u1", 8.0);
        usage("cust-u1", "po-u1", 4.3); // 12.3 total -> 2.3 over

        mockMvc.perform(post(MGMT + "/rateUsage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("cust-u1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount.value").value(5.75))
                .andExpect(jsonPath("$[0].name").value(
                        "EU roaming data overage: 2.3 GB over 10 GB included"));

        // Second rating pass: records already rated, same charges returned, none added.
        mockMvc.perform(post(MGMT + "/rateUsage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("cust-u1")))
                .andExpect(jsonPath("$.length()").value(1));

        // A light user pays nothing.
        allowance("po-u2", 10, 2.50);
        usage("cust-u2", "po-u2", 3.0);
        mockMvc.perform(post(MGMT + "/rateUsage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("cust-u2")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void consumptionReport_isPartyScoped_withBuckets() throws Exception {
        allowance("po-u3", 10, 2.50);
        usage("cust-u3", "po-u3", 2.3);

        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption").with(customer("cust-u3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket[0].name").value("EU roaming data"))
                .andExpect(jsonPath("$.bucket[0].usedValue").value(2.3))
                .andExpect(jsonPath("$.bucket[0].allowedValue").value(10));

        // Another customer sees an empty report, not this one's buckets.
        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption").with(customer("cust-nosy")))
                .andExpect(jsonPath("$.bucket.length()").value(0));
    }

    @Test
    void ingestRequiresTheMachineSeam() throws Exception {
        mockMvc.perform(post(MGMT + "/usage").with(customer("cust-u4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
