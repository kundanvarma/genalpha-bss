package com.bss.benefit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pool ledger through its API (#354): a plan's completion grants Choices,
 * a benefit holds and then commits them, a repeated item never spends twice,
 * two simultaneous picks for one free Choice cannot both win, and only
 * ordering's machine identity can move Choices.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChoiceLedgerApiTest {

    private static final String BASE = "/benefitAllowance/v1";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor ordering() {
        return jwt().authorities(new SimpleGrantedAuthority("benefit:write"));
    }

    private static RequestPostProcessor customer(String party) {
        return jwt().jwt(j -> j.subject(party)).authorities(new SimpleGrantedAuthority("customer"));
    }

    private void grant(String owner, int choices) throws Exception {
        mockMvc.perform(post(BASE + "/order/plan-" + UUID.randomUUID() + "/settle").with(ordering())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "grants": [{"orderItemId": "1", "offeringId": "po-plan",
                                 "offeringName": "GenAlpha Mobile Max", "choices": %d}]}
                                """.formatted(owner, choices)))
                .andExpect(status().isOk());
    }

    private String holdBody(String owner, String orderId, String itemId) {
        return """
                {"ownerPartyId": "%s", "orderId": "%s", "orderItemId": "%s", "offeringId": "po-stream",
                 "offeringName": "StreamCo Standard", "cost": 1}
                """.formatted(owner, orderId, itemId);
    }

    @Test
    void aPlanGrants_aPickHoldsThenCommits_andTheCustomerSeesTheirPool() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 2);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(owner, "ord-1", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("held"));
        mockMvc.perform(post(BASE + "/order/ord-1/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerPartyId\": \"" + owner + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/pool").with(customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offered").value(true))
                .andExpect(jsonPath("$.label").value("Choices"))
                .andExpect(jsonPath("$.granted").value(2))
                .andExpect(jsonPath("$.inUse").value(1))
                .andExpect(jsonPath("$.left").value(1))
                .andExpect(jsonPath("$.allocations[0].offeringName").value("StreamCo Standard"))
                .andExpect(jsonPath("$.allocations[0].state").value("committed"));
    }

    @Test
    void theSameItemHeldTwice_isTheSameAllocation_andSpendsOnce() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 2);
        String first = mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(owner, "ord-dup", "1"))).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(owner, "ord-dup", "1"))).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        mockMvc.perform(get(BASE + "/pool").with(customer(owner))).andExpect(jsonPath("$.inUse").value(1));
    }

    @Test
    void twoSimultaneousPicksForOneFreeChoice_oneWinsOneIsRefused() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> picks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String orderId = "ord-race-" + i;
            picks.add(pool.submit(() -> {
                start.await();
                return mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(owner, orderId, "1"))).andReturn();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcResult> f : picks) {
            statuses.add(f.get().getResponse().getStatus());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        mockMvc.perform(get(BASE + "/pool").with(customer(owner)))
                .andExpect(jsonPath("$.inUse").value(1))
                .andExpect(jsonPath("$.left").value(0));
    }

    @Test
    void aPickBeyondWhatIsLeft_isRefusedWithTheNumbers() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(owner, "ord-none", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("StreamCo Standard costs 1 of your Choices and you have 0 left"));
    }

    @Test
    void aCustomerCannotMoveChoices_norReadAnotherHousehold() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 2);
        mockMvc.perform(post(BASE + "/hold").with(customer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(owner, "ord-self", "1")))
                .andExpect(status().isForbidden());
        // naming someone else's pool reads one's own: the party comes from the token
        mockMvc.perform(get(BASE + "/pool").param("ownerPartyId", owner).with(customer("cust-other")))
                .andExpect(jsonPath("$.granted").value(0));
        mockMvc.perform(get(BASE + "/pool").param("ownerPartyId", owner)
                        .with(jwt().authorities(new SimpleGrantedAuthority("catalog:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTenantThatDoesNotOfferChoices_refusesAHold() throws Exception {
        mockMvc.perform(post(BASE + "/hold")
                        .with(jwt().jwt(j -> j.issuer("https://idp.tenant-b.test/realms/bss"))
                                .authorities(new SimpleGrantedAuthority("benefit:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody("cust-b", "ord-b", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choices are not offered here"));
    }

    @Test
    void aSwapSpendsTheChoiceItFrees_andCountsAsASwap() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 1);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(owner, "ord-a", "1"))).andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/order/ord-a/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerPartyId\": \"" + owner + "\"}")).andExpect(status().isOk());
        // no Choice left — but the swap order ends ord-a's benefit, so its Choice counts
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "orderId": "ord-b", "orderItemId": "2", "offeringId": "po-audio",
                                 "offeringName": "Lydbok+ Audiobooks", "cost": 1,
                                 "replaces": [{"orderId": "ord-a", "orderItemId": "1"}]}
                                """.formatted(owner)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/order/ord-b/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "endings": [{"orderId": "ord-a", "orderItemId": "1", "swapped": true}]}
                                """.formatted(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inUse").value(1))
                .andExpect(jsonPath("$.left").value(0))
                .andExpect(jsonPath("$.allocations[0].offeringName").value("Lydbok+ Audiobooks"))
                .andExpect(jsonPath("$.swapsThisQuarter").value(1));
    }

    @Test
    void aReleaseGivesTheChoiceBack_andIsNotASwap() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 1);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(owner, "ord-r", "1"))).andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/order/ord-r/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerPartyId\": \"" + owner + "\"}")).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/order/ord-end/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "endings": [{"orderId": "ord-r", "orderItemId": "1", "swapped": false}]}
                                """.formatted(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inUse").value(0))
                .andExpect(jsonPath("$.left").value(1))
                .andExpect(jsonPath("$.swapsThisQuarter").value(0));
    }

    @Test
    void aSwapCannotFreeSomeoneElsesChoice() throws Exception {
        String mine = "cust-" + UUID.randomUUID();
        String theirs = "cust-" + UUID.randomUUID();
        grant(theirs, 1);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(theirs, "ord-t", "1"))).andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/order/ord-t/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerPartyId\": \"" + theirs + "\"}")).andExpect(status().isOk());
        grant(mine, 1);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(mine, "ord-m1", "1"))).andExpect(status().isCreated());
        // my pool is full; naming THEIR allocation as "replaced" frees nothing of mine
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "orderId": "ord-m2", "orderItemId": "1", "offeringId": "po-x",
                                 "offeringName": "StreamCo Standard", "cost": 1,
                                 "replaces": [{"orderId": "ord-t", "orderItemId": "1"}]}
                                """.formatted(mine)))
                .andExpect(status().isConflict());
    }

    private void pickHeld(RequestPostProcessor as, String owner, String orderId, int months) throws Exception {
        mockMvc.perform(post(BASE + "/hold").with(as).contentType(MediaType.APPLICATION_JSON).content("""
                {"ownerPartyId": "%s", "orderId": "%s", "orderItemId": "1", "offeringId": "po-phone-%s",
                 "offeringName": "Phone offer (12 months)", "cost": 1, "holdMonths": %d}
                """.formatted(owner, orderId, orderId, months))).andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/order/" + orderId + "/settle").with(as).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerPartyId\": \"" + owner + "\"}")).andExpect(status().isOk());
    }

    @Test
    void aHoldStartsWhenTheBenefitIsInUse_andTheLawCapsIt() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 2);
        pickHeld(ordering(), owner, "ord-h12", 12);
        pickHeld(ordering(), owner, "ord-h36", 36);   // asks for more than the law allows
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        mockMvc.perform(get(BASE + "/pool").with(customer(owner)))
                .andExpect(jsonPath("$.allocations[?(@.orderId=='ord-h12')].heldUntil").value(
                        java.time.LocalDate.parse(today).plusMonths(12).toString()))
                .andExpect(jsonPath("$.allocations[?(@.orderId=='ord-h36')].heldUntil").value(
                        java.time.LocalDate.parse(today).plusMonths(24).toString()));
    }

    @Test
    void inNorway_noHoldApplies_untilTheLawIsAnswered() throws Exception {
        RequestPostProcessor norway = jwt().jwt(j -> j.issuer("https://idp.tenant-a.test/realms/bss"))
                .authorities(new SimpleGrantedAuthority("benefit:write"));
        String owner = "cust-" + UUID.randomUUID();
        mockMvc.perform(post(BASE + "/order/plan-no/settle").with(norway).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ownerPartyId": "%s", "grants": [{"orderItemId": "1", "offeringId": "po-plan",
                         "offeringName": "Mobil Max", "choices": 1}]}
                        """.formatted(owner))).andExpect(status().isOk());
        pickHeld(norway, owner, "ord-no", 12);
        mockMvc.perform(get(BASE + "/pool").param("ownerPartyId", owner).with(norway))
                .andExpect(jsonPath("$.allocations[0].heldUntil").doesNotExist());
    }

    @Test
    void aPlanChange_regrants_andAnEndedPlanGrantsNothing() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        mockMvc.perform(post(BASE + "/order/plan-r/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ownerPartyId": "%s", "grants": [{"orderItemId": "1", "offeringId": "po-big",
                         "offeringName": "Mobile Unlimited", "choices": 2}]}
                        """.formatted(owner))).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/order/change-1/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "regrants": [{"orderId": "plan-r", "orderItemId": "1",
                                 "offeringId": "po-small", "offeringName": "Mobile 60 GB", "choices": 1}]}
                                """.formatted(owner)))
                .andExpect(jsonPath("$.granted").value(1))
                .andExpect(jsonPath("$.grants[0].offeringName").value("Mobile 60 GB"));
        mockMvc.perform(post(BASE + "/order/cease-1/settle").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "regrants": [{"orderId": "plan-r", "orderItemId": "1", "choices": 0}]}
                                """.formatted(owner)))
                .andExpect(jsonPath("$.granted").value(0));
    }

    @Test
    void aGoodwillChoice_growsThePoolByOne_withItsReason() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 1);
        mockMvc.perform(post(BASE + "/goodwill").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerPartyId\": \"" + owner + "\", \"reason\": \"outage on 26 October\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.granted").value(2))
                .andExpect(jsonPath("$.grants[1].offeringName").value("Goodwill: outage on 26 October"));
        // the human approver (billing:admin) may grant it — but not hold or settle anything
        mockMvc.perform(post(BASE + "/goodwill").with(jwt().authorities(new SimpleGrantedAuthority("billing:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerPartyId\": \"" + owner + "\", \"reason\": \"second outage\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.granted").value(3));
        mockMvc.perform(post(BASE + "/hold").with(jwt().authorities(new SimpleGrantedAuthority("billing:admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(holdBody(owner, "ord-approver", "1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/goodwill").with(customer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerPartyId\": \"" + owner + "\", \"reason\": \"I deserve it\"}"))
                .andExpect(status().isForbidden());
    }

    @Autowired
    private com.bss.benefit.service.PartnerCosts partnerCosts;

    @Test
    void anActiveBenefitCostsItsPartnerOncePerMonth_andOnlyFinanceSeesIt() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        String partner = "PlayCo " + UUID.randomUUID();
        grant(owner, 2);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "orderId": "ord-cost", "orderItemId": "1", "offeringId": "po-arcade",
                                 "offeringName": "PlayCo Arcade", "cost": 1, "partner": "%s", "wholesaleCost": 4.00}
                                """.formatted(owner, partner)))
                .andExpect(status().isCreated());
        for (int i = 0; i < 2; i++) {   // the completion arrives twice
            mockMvc.perform(post(BASE + "/order/ord-cost/settle").with(ordering())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"ownerPartyId\": \"" + owner + "\"}"))
                    .andExpect(status().isOk());
        }
        partnerCosts.sweep();   // and the month's sweep runs over it too

        RequestPostProcessor finance = jwt().authorities(new SimpleGrantedAuthority("billing:admin"));
        String month = java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
        mockMvc.perform(get(BASE + "/partnerCost").param("period", month).with(finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.lines[?(@.partner == '%s')].active".formatted(partner)).value(1))
                .andExpect(jsonPath("$.lines[?(@.partner == '%s')].amount".formatted(partner)).value(4.0));
        mockMvc.perform(get(BASE + "/partnerCost").with(customer(owner))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/partnerCost").param("period", "October").with(finance))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPartnerInvoiceIsReconciledAgainstWhatWasOwed_recordedOnce_paidOnce() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        String partner = "TuneCo " + UUID.randomUUID();
        String month = java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
        grant(owner, 1);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerPartyId": "%s", "orderId": "ord-inv", "orderItemId": "1", "offeringId": "po-tune",
                                 "offeringName": "TuneCo Music", "cost": 1, "partner": "%s", "wholesaleCost": 40.00}
                                """.formatted(owner, partner)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/order/ord-inv/settle").with(ordering())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ownerPartyId\": \"" + owner + "\"}"))
                .andExpect(status().isOk());

        RequestPostProcessor finance = jwt().authorities(new SimpleGrantedAuthority("billing:admin"));
        String body = """
                {"partner": "%s", "period": "%s", "reference": "INV-2026-10-07", "amount": 42.50}
                """.formatted(partner, month);
        String first = mockMvc.perform(post(BASE + "/partnerInvoice").with(finance)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String again = mockMvc.perform(post(BASE + "/partnerInvoice").with(finance)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(again).isEqualTo(first);   // the same invoice twice is one invoice

        String row = "$.partners[?(@.partner == '%s')]".formatted(partner);
        mockMvc.perform(get(BASE + "/partnerCost").param("period", month).with(finance))
                .andExpect(jsonPath(row + ".owed").value(40.0))
                .andExpect(jsonPath(row + ".invoiced").value(42.5))
                .andExpect(jsonPath(row + ".difference").value(2.5))
                .andExpect(jsonPath(row + ".invoices.length()").value(1))
                .andExpect(jsonPath(row + ".invoices[0].paid").value(false));

        String id = first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(BASE + "/partnerInvoice/" + id + "/pay").with(finance))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paid").value(true));
        }
        mockMvc.perform(post(BASE + "/partnerInvoice").with(customer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/partnerInvoice").with(ordering())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/partnerInvoice").with(finance).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(month, "October"))).andExpect(status().isBadRequest());
    }

    @Test
    void aBenefitAlreadyPicked_isNotPickedTwice_unlessASwapEndsIt() throws Exception {
        String owner = "cust-" + UUID.randomUUID();
        grant(owner, 2);
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(owner, "dup-a-" + owner, "1"))).andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/hold").with(ordering()).contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(owner, "dup-b-" + owner, "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("StreamCo Standard is already picked"));
    }
}
