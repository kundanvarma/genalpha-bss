package com.bss.basemigration;

import com.bss.basemigration.client.AgreementClient;
import com.bss.basemigration.client.InventoryClient;
import com.bss.basemigration.client.OrderingClient;
import com.bss.basemigration.client.PartyClient;
import com.bss.basemigration.client.SimulationClient;
import com.bss.basemigration.security.TenantContext;
import com.bss.basemigration.service.MigrationEngine;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The base-migration engine, end to end on a fake clock: the rehearsal gate
 * (no simulation, no arming), candidate discovery with binding deferral, the
 * notice gate held as an inequality, the modify order, exit, rollback, the
 * circuit breaker, and the age/promo triggers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BaseMigrationApiTest {

    private static final String BASE = "/tmf-api/baseMigration/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MigrationEngine engine;

    @Autowired
    private Clock clock;

    @MockitoBean
    private InventoryClient inventory;

    @MockitoBean
    private OrderingClient ordering;

    @MockitoBean
    private AgreementClient agreements;

    @MockitoBean
    private PartyClient parties;

    @MockitoBean
    private SimulationClient simulations;

    @TestConfiguration
    static class FakeClockConfig {

        /** A clock the tests move by hand — the notice window is crossed by
         *  advancing time, never by weakening the gate. */
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {

        private volatile Instant instant = Instant.now();

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(
                new SimpleGrantedAuthority("migration:read"),
                new SimpleGrantedAuthority("migration:admin"));
    }

    private static RequestPostProcessor adminOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("migration:read"),
                new SimpleGrantedAuthority("migration:admin"));
    }

    private static RequestPostProcessor readOnly() {
        return jwt().authorities(new SimpleGrantedAuthority("migration:read"));
    }

    private static RequestPostProcessor readerOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer))
                .authorities(new SimpleGrantedAuthority("migration:read"));
    }

    private static Map<String, Object> product(String id, String partyId,
            String offeringId, String offeringName) {
        return Map.of(
                "id", id,
                "name", offeringName,
                "status", "active",
                "productOffering", Map.of("id", offeringId, "name", offeringName),
                "relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
    }

    private String createPlan(String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(BASE + "/migrationPlan").with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("draft"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("^\\{\"id\":\"([^\"]+)\".*$", "$1");
    }

    @Test
    void sunsetJourney_rehearsalGate_noticeGate_order_exit_rollback() throws Exception {
        when(simulations.simulationExists("sim-legacy")).thenReturn(true);
        when(inventory.listActiveProducts()).thenReturn(List.of(
                product("p-paula", "paula-1", "off-legacy", "Legacy Plan"),
                product("p-bindy", "bindy-1", "off-legacy", "Legacy Plan")));
        when(agreements.commitmentEnd(eq("paula-1"), eq("off-legacy"), any()))
                .thenReturn(Optional.empty());
        when(agreements.commitmentEnd(eq("bindy-1"), eq("off-legacy"), any()))
                .thenReturn(Optional.of(OffsetDateTime.now(clock).plusDays(60)));
        when(ordering.placeModifyOrder(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(Map.of("id", "ord-1", "state", "completed"));

        String planId = createPlan("""
                {"name": "Legacy sunset",
                 "matrix": [{"sourceOfferingId": "off-legacy", "targetOfferingId": "off-new",
                             "targetOfferingName": "New Plan",
                             "characteristicMap": {"simType": "keep"},
                             "deltaClass": "detrimental"}],
                 "eligibility": {"inBinding": "defer-to-expiry"},
                 "trigger": {"type": "bulk"},
                 "jurisdictionPack": {"noticeDays": 1}}
                """, admin());

        // THE REHEARSAL GATE: no simulation receipt, no arming — 409.
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/arm").with(admin()))
                .andExpect(status().isConflict());

        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/attachSimulation").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationRef\": \"sim-legacy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("simulated"));

        // Armed: discovery finds both; the in-binding customer is DEFERRED to expiry.
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/arm").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("armed"))
                .andExpect(jsonPath("$.customersDiscovered").value(2));

        // Tick 1: the out-of-binding, detrimental customer is NOTICED into the
        // exit window; no order may exist yet.
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            engine.runTenant("genalpha");
        }
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId + "/customer?state=exit-window")
                        .with(readOnly()))
                .andExpect(jsonPath("$[0].partyId").value("paula-1"))
                .andExpect(jsonPath("$[0].exitRight").value(true));
        verify(ordering, never()).placeModifyOrder(anyString(), anyString(), anyString(),
                any(), any(), anyString());

        // Tick 2, same clock: THE GATE HOLDS — noticeDays have not passed.
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            engine.runTenant("genalpha");
        }
        verify(ordering, never()).placeModifyOrder(anyString(), anyString(), anyString(),
                any(), any(), anyString());

        // The clock crosses the notice window: the ONE production write goes out.
        ((MutableClock) clock).advance(Duration.ofDays(1).plusHours(1));
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            engine.runTenant("genalpha");
        }
        verify(ordering).placeModifyOrder(eq("paula-1"), eq("p-paula"), eq("off-new"),
                eq("New Plan"), eq(Map.of("simType", "keep")), anyString());
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId + "/customer?state=migrated")
                        .with(readOnly()))
                .andExpect(jsonPath("$[0].partyId").value("paula-1"))
                .andExpect(jsonPath("$[0].orderRef").value("ord-1"));

        // The deferred customer is still waiting for their commitment's own end.
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId + "/progress").with(readOnly()))
                .andExpect(jsonPath("$.byState.migrated").value(1))
                .andExpect(jsonPath("$.byState.scheduled").value(1));

        // The exercised exit: penalty-free, because the change is detrimental.
        String bindyId = mockMvc.perform(
                        get(BASE + "/migrationPlan/" + planId + "/customer?state=scheduled")
                                .with(readOnly()))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("^\\[\\{\"id\":\"([^\"]+)\".*$", "$1");
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/customer/" + bindyId + "/exit")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("exited"))
                .andExpect(jsonPath("$.penaltyFreeExit").value(true));

        // Rollback: the INVERSE modify order, from the pre-migration snapshot.
        String paulaId = mockMvc.perform(
                        get(BASE + "/migrationPlan/" + planId + "/customer?state=migrated")
                                .with(readOnly()))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("^\\[\\{\"id\":\"([^\"]+)\".*$", "$1");
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/customer/" + paulaId + "/rollback")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rolled-back"));
        verify(ordering).placeModifyOrder(eq("paula-1"), eq("p-paula"), eq("off-legacy"),
                eq("Legacy Plan"), eq(Map.of()), anyString());

        // Nobody left mid-journey: the bulk plan closes itself.
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            engine.runTenant("genalpha");
        }
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId).with(readOnly()))
                .andExpect(jsonPath("$.state").value("done"));
    }

    @Test
    void circuitBreaker_pausesThePlanAfterConsecutiveFailures() throws Exception {
        // isolated on its own tenant so this wave never touches other tests' plans
        RequestPostProcessor staff = adminOf("https://idp.tenant-a.test/realms/bss");
        when(simulations.simulationExists("sim-breaker")).thenReturn(true);
        when(inventory.listActiveProducts()).thenReturn(List.of(
                product("p-b1", "party-b1", "off-old", "Old Plan"),
                product("p-b2", "party-b2", "off-old", "Old Plan")));
        when(agreements.commitmentEnd(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(ordering.placeModifyOrder(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("ordering rejected the modify"));

        String planId = createPlan("""
                {"name": "Breaker wave",
                 "matrix": [{"sourceOfferingId": "off-old", "targetOfferingId": "off-new2",
                             "deltaClass": "neutral"}],
                 "trigger": {"type": "bulk"},
                 "breakerThreshold": 2,
                 "jurisdictionPack": {"noticeDays": 0}}
                """, staff);
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/attachSimulation").with(staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationRef\": \"sim-breaker\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/arm").with(staff))
                .andExpect(jsonPath("$.customersDiscovered").value(2));

        try (TenantContext ignored = TenantContext.actAs("tenant-a")) {
            engine.runTenant("tenant-a");
        }

        // Two consecutive failures >= threshold: the wave PAUSES itself.
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId).with(staff))
                .andExpect(jsonPath("$.state").value("paused"))
                .andExpect(jsonPath("$.consecutiveFailures").value(2));
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId + "/customer?state=failed").with(staff))
                .andExpect(jsonPath("$.length()").value(2));

        // A human looks, fixes, resumes: the breaker resets.
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/resume").with(staff))
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.consecutiveFailures").value(0));
    }

    @Test
    void ageTrigger_autoMigrateSchedules_andGrandfatherOnlyFlags() throws Exception {
        // its own tenant: the journey test's engine runs must never see these plans
        RequestPostProcessor admin = adminOf("https://idp.tenant-b.test/realms/bss");
        RequestPostProcessor reader = readerOf("https://idp.tenant-b.test/realms/bss");
        LocalDate today = LocalDate.now(clock);
        when(simulations.simulationExists("sim-age")).thenReturn(true);
        when(parties.listIndividuals()).thenReturn(List.of(
                Map.of("id", "old-1", "birthDate", today.minusYears(40).toString()),
                Map.of("id", "young-1", "birthDate", today.minusYears(20).toString())));
        when(inventory.activeProductsOf("old-1")).thenReturn(List.of(
                product("p-old1", "old-1", "off-youth", "Youth Plan")));
        when(agreements.commitmentEnd(anyString(), anyString(), any())).thenReturn(Optional.empty());

        // auto-migrate: crossing the age threshold schedules the journey
        String autoPlan = createPlan("""
                {"name": "Youth plan roll-up",
                 "matrix": [{"sourceOfferingId": "off-youth", "targetOfferingId": "off-standard",
                             "deltaClass": "detrimental"}],
                 "trigger": {"type": "age-threshold", "ageYears": 30, "strategy": "auto-migrate"},
                 "jurisdictionPack": {"noticeDays": 0}}
                """, admin);
        mockMvc.perform(post(BASE + "/migrationPlan/" + autoPlan + "/attachSimulation").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationRef\": \"sim-age\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/migrationPlan/" + autoPlan + "/arm").with(admin))
                .andExpect(jsonPath("$.customersDiscovered").value(0));
        mockMvc.perform(post(BASE + "/migrationPlan/" + autoPlan + "/scanTriggers").with(admin))
                .andExpect(jsonPath("$.scheduled").value(1))
                .andExpect(jsonPath("$.grandfathered").value(0));
        mockMvc.perform(get(BASE + "/migrationPlan/" + autoPlan + "/customer?state=scheduled")
                        .with(reader))
                .andExpect(jsonPath("$[0].partyId").value("old-1"));
        verify(inventory, never()).activeProductsOf("young-1");

        // lapse-to-grandfather: the same birthday only FLAGS the party
        String gfPlan = createPlan("""
                {"name": "Youth plan grandfathering",
                 "matrix": [{"sourceOfferingId": "off-youth", "targetOfferingId": "off-standard",
                             "deltaClass": "detrimental"}],
                 "trigger": {"type": "age-threshold", "ageYears": 30, "strategy": "lapse-to-grandfather"},
                 "jurisdictionPack": {"noticeDays": 0}}
                """, admin);
        mockMvc.perform(post(BASE + "/migrationPlan/" + gfPlan + "/attachSimulation").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationRef\": \"sim-age\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/migrationPlan/" + gfPlan + "/arm").with(admin))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/migrationPlan/" + gfPlan + "/scanTriggers").with(admin))
                .andExpect(jsonPath("$.grandfathered").value(1))
                .andExpect(jsonPath("$.scheduled").value(0));
        mockMvc.perform(get(BASE + "/migrationPlan/" + gfPlan).with(reader))
                .andExpect(jsonPath("$.grandfatheredPartyIds[0]").value("old-1"));
        mockMvc.perform(get(BASE + "/migrationPlan/" + gfPlan + "/customer").with(reader))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void promoExpiry_schedulesCourtesyNotices_withoutExitRight() throws Exception {
        // its own tenant: no engine run in another test may pick these up
        RequestPostProcessor admin = adminOf("https://idp.tenant-a.test/realms/bss");
        RequestPostProcessor reader = readerOf("https://idp.tenant-a.test/realms/bss");
        LocalDate today = LocalDate.now(clock);
        when(simulations.simulationExists("sim-promo")).thenReturn(true);
        when(inventory.listActiveProducts()).thenReturn(List.of(
                product("p-promo1", "promo-1", "off-promo", "Intro Offer")));
        when(agreements.commitmentEnd(anyString(), anyString(), any())).thenReturn(Optional.empty());

        String planId = createPlan("""
                {"name": "Intro offer roll-off",
                 "matrix": [{"sourceOfferingId": "off-promo", "targetOfferingId": "off-standard",
                             "deltaClass": "detrimental"}],
                 "trigger": {"type": "promo-expiry", "endDate": "%s"},
                 "jurisdictionPack": {"noticeDays": 0}}
                """.formatted(today), admin);
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/attachSimulation").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationRef\": \"sim-promo\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/arm").with(admin))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/scanTriggers").with(admin))
                .andExpect(jsonPath("$.due").value(true))
                .andExpect(jsonPath("$.scheduled").value(1));

        // disclosed at sale: a courtesy notice, NO exit right — even though the
        // delta is detrimental
        mockMvc.perform(get(BASE + "/migrationPlan/" + planId + "/customer").with(reader))
                .andExpect(jsonPath("$[0].exitRight").value(false));
    }

    @Test
    void validation_badSimulationRef_missingMatrix_unknownDeltaClass() throws Exception {
        when(simulations.simulationExists("no-such-sim")).thenReturn(false);
        mockMvc.perform(post(BASE + "/migrationPlan").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"No matrix\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/migrationPlan").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bad delta",
                                 "matrix": [{"sourceOfferingId": "a", "targetOfferingId": "b",
                                             "deltaClass": "catastrophic"}]}
                                """))
                .andExpect(status().isBadRequest());

        String planId = createPlan("""
                {"name": "Ref check",
                 "matrix": [{"sourceOfferingId": "a", "targetOfferingId": "b"}],
                 "jurisdictionPack": {"noticeDays": 0}}
                """, admin());
        // a simulationRef the simulator has never seen does not open the gate
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/attachSimulation").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationRef\": \"no-such-sim\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/migrationPlan/" + planId + "/arm").with(admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void security_anonymousIsRejected_readerCannotWrite() throws Exception {
        mockMvc.perform(get(BASE + "/migrationPlan"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/migrationPlan").with(readOnly())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/migrationPlan").with(readOnly()))
                .andExpect(status().isOk());
    }
}
