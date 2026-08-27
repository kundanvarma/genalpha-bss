package com.bss.usage;

import com.bss.usage.repository.AllowanceBoostRepository;
import com.bss.usage.security.TenantRegistry;
import com.bss.usage.security.TenantScope;
import com.bss.usage.service.TenantClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The usage-policy layer: household pool (reserve-then-commit, member hard
 * caps), the three spend-meter faces (statutory content floor, roaming
 * cut-off + explicit continue), opt-in auto top-up (consent, breach-window
 * idempotency, per-cycle caps, cycle reset via the injectable clock) and
 * travel passes (zone + validity window filtering in the rating pass).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsagePolicyApiTest {

    private static final String MGMT = "/tmf-api/usageManagement/v4";
    private static final String CONSUMPTION = "/tmf-api/usageConsumption/v4";

    /** The injectable cycle: auto top-up and the meters read this clock. */
    @TestConfiguration
    static class FixedClockConfig {
        static final AtomicReference<LocalDate> TODAY = new AtomicReference<>(LocalDate.now());

        @Bean
        @Primary
        TenantClock fixedTenantClock(TenantRegistry registry, TenantScope scope) {
            return new TenantClock(registry, scope) {
                @Override
                public LocalDate today() {
                    return TODAY.get();
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AllowanceBoostRepository boosts;

    @BeforeEach
    void resetClock() {
        FixedClockConfig.TODAY.set(LocalDate.now());
    }

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

    private void allowance(String offeringId, String usageType, double allowed,
            double pricePerUnit, boolean boost) throws Exception {
        mockMvc.perform(post(MGMT + "/usageAllowance").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "%s", "name": "Plan"},
                                 "usageType": "%s",
                                 "allowance": {"value": %s, "units": "GB"},
                                 "overagePrice": {"unit": "EUR", "value": %s},
                                 "boost": %s}
                                """.formatted(offeringId, usageType, allowed, pricePerUnit, boost)))
                .andExpect(status().isCreated());
    }

    private void usage(String party, String offeringId, String usageType, double gb,
            String zone, OffsetDateTime date) throws Exception {
        String zoneField = zone == null ? "" : "\"zone\": \"" + zone + "\",";
        String dateField = date == null ? "" : "\"usageDate\": \"" + date + "\",";
        mockMvc.perform(post(MGMT + "/usage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usageType": "%s", %s %s
                                 "usageCharacteristic": {"value": %s, "units": "GB"},
                                 "productOffering": {"id": "%s"},
                                 "relatedParty": [{"id": "%s", "role": "customer"}]}
                                """.formatted(usageType, zoneField, dateField, gb, offeringId, party)))
                .andExpect(status().isCreated());
    }

    private String rateBody(String party) {
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        return "{\"relatedPartyId\": \"" + party + "\", \"periodStart\": \"" + start
                + "\", \"periodEnd\": \"" + start.plusMonths(1).minusDays(1) + "\"}";
    }

    // ---------------- household pool ----------------

    @Test
    void poolLifecycle_memberHardCap_reserveThenCommit_andFallbackRating() throws Exception {
        allowance("po-pool", "pool data", 1, 1.00, false);
        String poolId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post(MGMT + "/allowancePool").with(machine())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"ownerPartyId": "pool-owner", "name": "Family pool",
                                         "usageType": "pool data", "poolGB": 10}
                                        """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.poolGB").value(10))
                        .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post(MGMT + "/allowancePool/" + poolId + "/member").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partyId\": \"pool-m1\", \"hardLimitGB\": 2, \"softLimitGB\": 1}"))
                .andExpect(status().isCreated());

        // a customer without a live household link cannot manage the pool
        // (the check fails CLOSED when the party source is unreachable)
        mockMvc.perform(post(MGMT + "/allowancePool/" + poolId + "/member").with(customer("pool-m1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partyId\": \"pool-m2\"}"))
                .andExpect(status().isNotFound());

        // 3 GB arrives: the pool grants 2 (the member's hard cap), 1 falls back
        usage("pool-m1", "po-pool", "pool data", 3.0, null, null);
        // 2 more: member at hard cap — nothing more leaves the pool
        usage("pool-m1", "po-pool", "pool data", 2.0, null, null);

        mockMvc.perform(get(MGMT + "/allowancePool/" + poolId).with(machine()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumedGB").value(2.0))
                .andExpect(jsonPath("$.remainingGB").value(8.0))
                .andExpect(jsonPath("$.member[?(@.partyId=='pool-m1')].consumedGB").value(2.0));

        // TMF677: the member's personal bucket shows only the unpooled share,
        // and the pool section rides the report
        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption").with(customer("pool-m1")))
                .andExpect(jsonPath("$.bucket[0].usedValue").value(3.0))
                .andExpect(jsonPath("$.pool.remainingGB").value(8.0));

        // rating charges only the unpooled overage: 3 unpooled - 1 allowance = 2
        mockMvc.perform(post(MGMT + "/rateUsage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("pool-m1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount.value").value(2.00));

        // member caps are patchable, removal works, owner row stays
        mockMvc.perform(patch(MGMT + "/allowancePool/" + poolId + "/member/pool-m1").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hardLimitGB\": 5}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(MGMT + "/allowancePool/" + poolId + "/member/pool-m1").with(machine()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(MGMT + "/allowancePool/" + poolId + "/member/pool-owner").with(machine()))
                .andExpect(status().isBadRequest());
    }

    // ---------------- spend meters ----------------

    @Test
    void contentCap_statutoryFloorRejected_barringBlocksAndIsFree() throws Exception {
        // below the country pack's floor (250 NOK): refused, not clamped
        mockMvc.perform(patch(MGMT + "/spendPolicy/content").with(customer("cust-content"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\": 100}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(MGMT + "/spendPolicy/content").with(customer("cust-content"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\": 250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit.value").value(250));

        // barring: free, always available, and it blocks the charge
        mockMvc.perform(patch(MGMT + "/spendPolicy/content").with(customer("cust-content"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barred\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barred").value(true));
        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-content", "chargeClass": "content",
                                 "amount": {"value": 50, "unit": "NOK"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        // unbarred: charges accrue until the cap wall, then refuse
        mockMvc.perform(patch(MGMT + "/spendPolicy/content").with(customer("cust-content"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barred\": false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-content", "chargeClass": "content",
                                 "amount": {"value": 260, "unit": "NOK"}}
                                """))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.meter[0].blocked").value(true));
        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-content", "chargeClass": "content",
                                 "amount": {"value": 10, "unit": "NOK"}}
                                """))
                .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void roamingLimit_defaultsOn_cutsOffAt100_explicitContinueRestores() throws Exception {
        // the default financial limit exists without the customer ever asking
        mockMvc.perform(get(MGMT + "/spendPolicy").with(customer("cust-roam")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.meterType=='roaming')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.meterType=='roaming')].limit.value").value(50));

        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-roam", "chargeClass": "roaming",
                                 "amount": {"value": 45, "unit": "EUR"}}
                                """))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.meter[0].blocked").value(false));
        // 55 ≥ 50: hard cut-off
        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-roam", "chargeClass": "roaming",
                                 "amount": {"value": 10, "unit": "EUR"}}
                                """))
                .andExpect(jsonPath("$.meter[0].blocked").value(true));
        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-roam", "chargeClass": "roaming",
                                 "amount": {"value": 5, "unit": "EUR"}}
                                """))
                .andExpect(jsonPath("$.accepted").value(false));

        // the audited escape hatch: the customer's own explicit continue
        mockMvc.perform(post(MGMT + "/roamingLimit/continue").with(customer("cust-roam"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continueElected").value(true))
                .andExpect(jsonPath("$.blocked").value(false));
        mockMvc.perform(post(MGMT + "/spendMeter/charge").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "cust-roam", "chargeClass": "roaming",
                                 "amount": {"value": 5, "unit": "EUR"}}
                                """))
                .andExpect(jsonPath("$.accepted").value(true));
    }

    // ---------------- auto top-up ----------------

    @Test
    void autoTopup_requiresConsent_isIdempotentPerWindow_capsPerCycle_resetsNextCycle()
            throws Exception {
        allowance("po-at-plan", "at data", 10, 2.50, false);
        allowance("po-at-boost", "at data", 5, 0, true);
        usage("at-cust", "po-at-plan", "at data", 9.0, null, null);

        // never default-on: enabling without consent is refused
        mockMvc.perform(put(MGMT + "/autoTopupPolicy?partyId=at-cust").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": true, "boostOfferingId": "po-at-boost"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(MGMT + "/autoTopupPolicy?partyId=at-cust").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": true, "consent": true, "boostOfferingId": "po-at-boost",
                                 "trigger": "depletion", "maxBoostsPerCycle": 2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentAt").exists());

        // depletion breach: one boost — and the SAME window replayed buys nothing
        String breach = """
                {"partyId": "at-cust", "percentUsed": 100, "threshold": 100, "windowId": "%s"}
                """;
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(breach.formatted("w1")))
                .andExpect(status().isAccepted());
        assertThat(autoTopupCount("at-cust")).isEqualTo(1);
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(breach.formatted("w1")))
                .andExpect(status().isAccepted());
        assertThat(autoTopupCount("at-cust")).isEqualTo(1);

        // the boost shows on the meter: 10 base + 5 boost
        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption").with(customer("at-cust")))
                .andExpect(jsonPath("$.bucket[0].allowedValue").value(15));

        // a new window buys the second; the third hits the per-cycle cap
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(breach.formatted("w2")))
                .andExpect(status().isAccepted());
        assertThat(autoTopupCount("at-cust")).isEqualTo(2);
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(breach.formatted("w3")))
                .andExpect(status().isAccepted());
        assertThat(autoTopupCount("at-cust")).isEqualTo(2);

        // next cycle (the injectable clock): the cap resets
        FixedClockConfig.TODAY.set(LocalDate.now().plusMonths(1).withDayOfMonth(3));
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(breach.formatted("w4")))
                .andExpect(status().isAccepted());
        assertThat(autoTopupCount("at-cust")).isEqualTo(3);
        FixedClockConfig.TODAY.set(LocalDate.now());

        // disabled: breaches buy nothing
        mockMvc.perform(put(MGMT + "/autoTopupPolicy?partyId=at-cust").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/ocs/usageThreshold")
                        .contentType(MediaType.APPLICATION_JSON).content(breach.formatted("w5")))
                .andExpect(status().isAccepted());
        assertThat(autoTopupCount("at-cust")).isEqualTo(3);
    }

    private long autoTopupCount(String party) {
        return boosts.findAll().stream()
                .filter(b -> party.equals(b.getOwnerPartyId()) && "auto-topup".equals(b.getSource()))
                .count();
    }

    // ---------------- travel pass ----------------

    @Test
    void travelPass_coversZoneUsageInWindow_notOutside() throws Exception {
        allowance("po-tp", "tp data", 10, 2.00, false);
        mockMvc.perform(post(MGMT + "/travelPass").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyId": "tp-cust", "usageType": "tp data", "zone": "world-1",
                                 "amountGB": 5, "validFrom": "%s", "validityDays": 3}
                                """.formatted(OffsetDateTime.now().minusDays(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone").value("world-1"));

        // home usage exhausts the plan exactly
        usage("tp-cust", "po-tp", "tp data", 10.0, null, null);
        // in-window zone usage: the pass carries it (first record announces the zone)
        mockMvc.perform(post(MGMT + "/usage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usageType": "tp data", "zone": "world-1",
                                 "usageCharacteristic": {"value": 3, "units": "GB"},
                                 "productOffering": {"id": "po-tp"},
                                 "relatedParty": [{"id": "tp-cust", "role": "customer"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneEntered").value(true));
        usage("tp-cust", "po-tp", "tp data", 1.0, "world-1", null);
        // outside the pass window (before validFrom): rates like home usage
        usage("tp-cust", "po-tp", "tp data", 2.0, "world-1",
                OffsetDateTime.now().minusDays(10));

        // one rating pass: 10 home + 4 zone (pass-covered) + 2 out-of-window
        // = 12 chargeable vs 10 allowed -> 2 GB overage at 2.00
        mockMvc.perform(post(MGMT + "/rateUsage").with(machine())
                        .contentType(MediaType.APPLICATION_JSON).content(rateBody("tp-cust")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount.value").value(4.00));

        // the report splits home and zone buckets
        mockMvc.perform(get(CONSUMPTION + "/queryUsageConsumption").with(customer("tp-cust")))
                .andExpect(jsonPath("$.bucket[?(@.zone=='world-1')].usedValue").value(6.0));
    }
}
