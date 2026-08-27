package com.bss.billing;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.service.TenantClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Collections: the statutory floor refuses undercutting policies; the sweeper
 * walks the ladder on a FAKE clock (reminder only after the fee gate,
 * enforcement only a month after the demand); holds pause it; a settlement
 * cures it — services reinstate and the reconnection fee rides the next bill.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CollectionsApiTest {

    private static final String BASE = "/tmf-api/customerBillManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.bss.billing.repository.CollectionCaseRepository caseRepository;

    @Autowired
    private com.bss.billing.service.DunningService dunningService;

    @MockBean
    private DownstreamClients.InventoryClient inventoryClient;

    @MockBean
    private DownstreamClients.CatalogClient catalogClient;

    @MockBean
    private DownstreamClients.PaymentClient paymentClient;

    @MockBean
    private DownstreamClients.UsageClient usageClient;

    @MockBean
    private DownstreamClients.PromotionClient promotionClient;

    @MockBean
    private DownstreamClients.PricingClient pricingClient;

    @MockBean
    private DownstreamClients.OrgClient orgClient;

    @MockBean
    private DownstreamClients.SomClient somClient;

    @MockBean
    private TenantClock clock;

    private final OffsetDateTime base = OffsetDateTime.now();

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"),
                new SimpleGrantedAuthority("billing:admin"));
    }

    /** The fake clock: the whole ladder lives on it. */
    private void daysAhead(int days) {
        given(clock.now()).willReturn(base.plusDays(days));
        given(clock.today()).willReturn(LocalDate.now().plusDays(days));
    }

    private void mockPlan(String owner) {
        given(inventoryClient.activeProducts()).willReturn(List.of(
                Map.of("id", "prod-" + owner, "name", "Fiber 1000",
                        "productOffering", Map.of("id", "po-fiber"),
                        "relatedParty", List.of(Map.of("id", owner, "role", "customer")))));
        given(catalogClient.offering("po-fiber")).willReturn(
                Map.of("id", "po-fiber", "productOfferingPrice", List.of(Map.of("id", "price-fiber"))));
        given(catalogClient.price("price-fiber")).willReturn(
                Map.of("priceType", "recurring", "price", Map.of("unit", "NOK", "value", 399.00)));
    }

    private static final String VALID_STEPS = """
            [{"offsetDays":14,"action":"remind","templateId":"dunning-reminder","feeType":"reminderFee","feeAmount":38},
             {"offsetDays":16,"action":"warn","templateId":"dunning-warning"},
             {"offsetDays":46,"action":"restrict","templateId":"dunning-restricted"},
             {"offsetDays":48,"action":"suspend","templateId":"dunning-suspended"}]""";

    private String policyBody(String steps) {
        return """
                {"name":"default","country":"NO","paymentTermDays":14,"entryThreshold":250,
                 "currency":"NOK","reconnectionFee":100,"writeOffThreshold":500,
                 "promiseMaxPerPeriod":2,"promisePeriodDays":90,"promiseMaxDays":14,
                 "steps":""" + steps + "}";
    }

    private void ensurePolicy() throws Exception {
        MvcResult existing = mockMvc.perform(get(BASE + "/dunningPolicy").with(admin())).andReturn();
        if ("[]".equals(existing.getResponse().getContentAsString().trim())) {
            mockMvc.perform(post(BASE + "/dunningPolicy").with(admin())
                            .contentType("application/json").content(policyBody(VALID_STEPS)))
                    .andExpect(status().isCreated());
        }
    }

    private String runAndGetBillId(String owner) throws Exception {
        mockPlan(owner);
        mockMvc.perform(post(BASE + "/billingRun").with(staff())).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(get(BASE + "/customerBill?relatedPartyId=" + owner + "&limit=100")
                        .with(staff()))
                .andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$[0].id");
    }

    private void sweep() throws Exception {
        mockMvc.perform(post(BASE + "/collectionSweep").with(admin())).andExpect(status().isOk());
    }

    private String caseOf(String owner) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/collectionCase").with(staff()))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> cases = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$[?(@.accountId == '" + owner + "')]");
        return cases.isEmpty() ? null : String.valueOf(cases.get(0).get("id"));
    }

    private String stateOf(String owner) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/collectionCase").with(staff()))
                .andExpect(status().isOk()).andReturn();
        List<String> states = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(),
                "$[?(@.accountId == '" + owner + "')].state");
        return states.isEmpty() ? null : states.get(0);
    }

    // ---- the statutory floor ----

    @Test
    void statutoryPack_refusesPoliciesUnderTheFloor() throws Exception {
        daysAhead(0);
        // a fee before the 14-day gate
        mockMvc.perform(post(BASE + "/dunningPolicy").with(admin()).contentType("application/json")
                        .content(policyBody("""
                                [{"offsetDays":10,"action":"remind","feeType":"reminderFee","feeAmount":38}]""")))
                .andExpect(status().isBadRequest());
        // a fee above the cap
        mockMvc.perform(post(BASE + "/dunningPolicy").with(admin()).contentType("application/json")
                        .content(policyBody("""
                                [{"offsetDays":14,"action":"remind","feeType":"reminderFee","feeAmount":60}]""")))
                .andExpect(status().isBadRequest());
        // three fee-bearing reminders
        mockMvc.perform(post(BASE + "/dunningPolicy").with(admin()).contentType("application/json")
                        .content(policyBody("""
                                [{"offsetDays":14,"action":"remind","feeType":"reminderFee","feeAmount":38},
                                 {"offsetDays":20,"action":"remind","feeType":"reminderFee","feeAmount":38},
                                 {"offsetDays":26,"action":"remind","feeType":"reminderFee","feeAmount":38}]""")))
                .andExpect(status().isBadRequest());
        // enforcement without a demand+warning step
        mockMvc.perform(post(BASE + "/dunningPolicy").with(admin()).contentType("application/json")
                        .content(policyBody("""
                                [{"offsetDays":14,"action":"remind"},{"offsetDays":50,"action":"suspend"}]""")))
                .andExpect(status().isBadRequest());
        // enforcement before the one-month notice has run
        mockMvc.perform(post(BASE + "/dunningPolicy").with(admin()).contentType("application/json")
                        .content(policyBody("""
                                [{"offsetDays":16,"action":"warn"},{"offsetDays":30,"action":"restrict"}]""")))
                .andExpect(status().isBadRequest());
        // an entry threshold under the minimum actionable amount
        mockMvc.perform(post(BASE + "/dunningPolicy").with(admin()).contentType("application/json")
                        .content(policyBody(VALID_STEPS).replace("\"entryThreshold\":250",
                                "\"entryThreshold\":100")))
                .andExpect(status().isBadRequest());
        // the merged PATCH result is validated too — no sneaking under
        ensurePolicy();
        MvcResult list = mockMvc.perform(get(BASE + "/dunningPolicy").with(admin())).andReturn();
        String policyId = com.jayway.jsonpath.JsonPath.read(list.getResponse().getContentAsString(), "$[0].id");
        mockMvc.perform(patch(BASE + "/dunningPolicy/" + policyId).with(admin())
                        .contentType("application/json")
                        .content("""
                                {"steps":[{"offsetDays":5,"action":"remind","feeType":"reminderFee","feeAmount":38}]}"""))
                .andExpect(status().isBadRequest());
        // and the statutory block rides the policy view, read-only
        mockMvc.perform(get(BASE + "/dunningPolicy/" + policyId).with(admin()))
                .andExpect(jsonPath("$.statutory.reminderFeeGateDays").value(14))
                .andExpect(jsonPath("$.statutory.minActionableAmount").value(250.0));
        // policy writes are billing:admin — staff without it gets 403
        mockMvc.perform(post(BASE + "/dunningPolicy").with(staff()).contentType("application/json")
                        .content(policyBody(VALID_STEPS)))
                .andExpect(status().isForbidden());
    }

    // ---- the ladder ----

    @Test
    void ladder_walksRemindWarnRestrictSuspend_onTheStatutoryClock() throws Exception {
        ensurePolicy();
        String owner = "cust-ladder";
        given(somClient.servicesOf(owner)).willReturn(List.of(
                Map.of("id", "svc-ladder", "state", "active")));
        daysAhead(0);
        String billId = runAndGetBillId(owner);

        // day 27 (due day 14 + 13): the fee gate has NOT run — no step, no fee
        daysAhead(27);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner))
                .satisfiesAnyOf(s -> org.assertj.core.api.Assertions.assertThat(s).isNull(),
                        s -> org.assertj.core.api.Assertions.assertThat(s).isEqualTo("current"));

        // day 29: reminder fires WITH the capped fee, onto the oldest bill
        daysAhead(29);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("reminded");
        mockMvc.perform(get(BASE + "/customerBill/" + billId).with(staff()))
                .andExpect(jsonPath("$.amountDue.value").value(437.00));
        mockMvc.perform(get(BASE + "/customerBill/" + billId + "/appliedCustomerBillingRate").with(staff()))
                .andExpect(jsonPath("$[?(@.type == 'reminderFee')]").isNotEmpty());

        // day 40: the demand + advance warning (late on purpose — the notice
        // clock must run from the REAL warning, not the policy's paper date)
        daysAhead(40);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("warned");

        // day 62: the restrict rung's offset (due+46 = day 60) has passed,
        // but the warning went out day 40 — a month has NOT run. Waits.
        daysAhead(62);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("warned");
        verify(somClient, never()).restrict(anyString(), anyString(), any());

        // day 71: a month after the warning — restriction, emergency whitelist on
        daysAhead(71);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("restricted");
        verify(somClient).restrict(eq("svc-ladder"), eq("nonpayment"),
                org.mockito.ArgumentMatchers.argThat(p -> Boolean.TRUE.equals(p.get("emergencyWhitelist"))));

        // day 72: the suspend rung
        daysAhead(72);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("suspended");
        verify(somClient).suspend("svc-ladder", "nonpayment");

        // paying the bill (fee included) CURES: resume + reconnection fee line
        given(paymentClient.validateAuthorized(anyString(), anyString(), any())).willReturn("");
        mockMvc.perform(patch(BASE + "/customerBill/" + billId).with(customer(owner))
                        .contentType("application/json")
                        .content("{\"state\":\"settled\",\"payment\":[{\"id\":\"pay-ladder\"}]}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("current");
        verify(somClient).resume("svc-ladder");
        mockMvc.perform(get(BASE + "/appliedCustomerBillingRate?isBilled=false").with(staff()))
                .andExpect(jsonPath("$[?(@.type == 'reconnectionFee')]").isNotEmpty());
    }

    // ---- MRC stops while suspended for nonpayment ----

    @Test
    void billingRun_skipsRecurringCharges_whileSuspendedForNonpayment() throws Exception {
        ensurePolicy();
        daysAhead(0);
        String owner = "cust-frozen";
        com.bss.billing.entity.CollectionCase frozen = new com.bss.billing.entity.CollectionCase();
        frozen.setId(java.util.UUID.randomUUID().toString());
        frozen.setTenantId("genalpha");
        frozen.setAccountId(owner);
        frozen.setState(com.bss.billing.entity.CollectionCase.SUSPENDED);
        frozen.setCreatedAt(OffsetDateTime.now());
        frozen.setLastUpdate(OffsetDateTime.now());
        caseRepository.save(frozen);

        mockPlan(owner);
        mockMvc.perform(post(BASE + "/billingRun").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billsCreated").value(0));
        mockMvc.perform(get(BASE + "/customerBill?relatedPartyId=" + owner).with(staff()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- promise-to-pay pauses; a broken promise resumes ----

    @Test
    void promiseToPay_pausesTheLadder_andBreakingItResumes() throws Exception {
        ensurePolicy();
        String owner = "cust-promise";
        daysAhead(0);
        runAndGetBillId(owner);
        daysAhead(29);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("reminded");

        // the CUSTOMER promises from their own case (PartyScope)
        String caseId = caseOf(owner);
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/promiseToPay")
                        .with(customer(owner)).contentType("application/json")
                        .content("{\"days\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds.promiseToPay.dueAt").isNotEmpty());
        // a second standing promise refuses
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/promiseToPay")
                        .with(customer(owner)).contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        // a foreign customer cannot even see the case
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/promiseToPay")
                        .with(customer("cust-other")).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());

        // day 31: warn would fire, but the promise holds the ladder
        daysAhead(31);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("reminded");

        // day 38: the promise passed unpaid — broken, and the ladder resumes
        daysAhead(38);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("warned");
        mockMvc.perform(get(BASE + "/collectionCase/" + caseId).with(staff()))
                .andExpect(jsonPath("$.holds.promiseToPay").doesNotExist());
    }

    // ---- dispute hold is amount-scoped ----

    @Test
    void disputeHold_freezesOnlyTheDisputedAmount() throws Exception {
        ensurePolicy();
        String owner = "cust-dispute";
        daysAhead(0);
        runAndGetBillId(owner);
        daysAhead(29);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("reminded");
        String caseId = caseOf(owner);

        // freeze 200 of 437: the remainder (237) is under the statutory floor
        // — the ladder freezes where it stands (no advance, no false cure)
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/hold").with(admin())
                        .contentType("application/json").content("{\"type\":\"dispute\",\"amount\":200}"))
                .andExpect(status().isOk());
        daysAhead(31);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("reminded");

        // a smaller hold leaves the rest actionable — it keeps aging
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/hold").with(admin())
                        .contentType("application/json").content("{\"type\":\"dispute\",\"amount\":100}"))
                .andExpect(status().isOk());
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("warned");

        // hardship holds everything, released it walks again
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/hold").with(admin())
                        .contentType("application/json").content("{\"type\":\"hardship\"}"))
                .andExpect(status().isOk());
        daysAhead(80);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("warned");
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/release").with(admin())
                        .contentType("application/json").content("{\"type\":\"hardship\"}"))
                .andExpect(status().isOk());
        // holds are back-office: plain staff/customer get 403
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/hold").with(staff())
                        .contentType("application/json").content("{\"type\":\"hardship\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- a broken installment plan feeds the same case ----

    @Test
    void brokenInstallmentPlan_feedsTheCollectionCase() throws Exception {
        ensurePolicy();
        String owner = "cust-plan";
        daysAhead(0);
        String billId = runAndGetBillId(owner);
        mockMvc.perform(post(BASE + "/customerBill/" + billId + "/installmentPlan")
                        .with(customer(owner)).contentType("application/json")
                        .content("{\"installments\":3}"))
                .andExpect(status().isOk());

        // on schedule mid-plan: nothing to collect even though the bill's own
        // due date has long passed
        daysAhead(20);
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isNull();

        // the plan goes overdue: the old dunning reminds once, then BREAKS —
        // and the remainder lands in the SAME collection case
        daysAhead(45);
        dunningService.sweepTenant("genalpha");
        dunningService.sweepTenant("genalpha");
        sweep();
        org.assertj.core.api.Assertions.assertThat(stateOf(owner)).isEqualTo("reminded");
        mockMvc.perform(get(BASE + "/collectionCase/" + caseOf(owner)).with(staff()))
                .andExpect(jsonPath("$.overdueBalance.value").value(399.00));
    }

    // ---- write-off ----

    @Test
    void writeOff_needsAdminReasonAndThreshold() throws Exception {
        ensurePolicy();
        String owner = "cust-writeoff";
        daysAhead(0);
        String billId = runAndGetBillId(owner);
        daysAhead(29);
        sweep();
        String caseId = caseOf(owner);

        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/writeOff").with(staff())
                        .contentType("application/json").content("{\"reason\":\"gone\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/writeOff").with(admin())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/collectionCase/" + caseId + "/writeOff").with(admin())
                        .contentType("application/json").content("{\"reason\":\"estate closed, no assets\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("writtenOff"));
        mockMvc.perform(get(BASE + "/customerBill/" + billId).with(staff()))
                .andExpect(jsonPath("$.state").value("writtenOff"));
        // the customer sees their own case summary; a stranger sees nothing
        mockMvc.perform(get(BASE + "/collectionCase").with(customer(owner)))
                .andExpect(jsonPath("$[0].state").value("writtenOff"));
        mockMvc.perform(get(BASE + "/collectionCase").with(customer("cust-nobody")))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
