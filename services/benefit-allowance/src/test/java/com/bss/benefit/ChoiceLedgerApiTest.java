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
}
