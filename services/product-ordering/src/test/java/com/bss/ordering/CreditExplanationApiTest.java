package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.PolicyClient;
import com.bss.ordering.client.PromotionClient;
import com.bss.ordering.client.ReviewTicketClient;
import com.bss.ordering.entity.CreditDecision;
import com.bss.ordering.exception.DownstreamException;
import com.bss.ordering.repository.CreditDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credit explanations (#422): every decision keeps WHY (a code and a sentence)
 * against the order it was taken for; the order's owner — and nobody else —
 * can read it (a business admin: only its own organization's members), with
 * the data categories used, what actually happened to the order, and the way
 * to a person — also when the operator's rule refused the order and only the
 * decision is left; asking a person to look again files exactly one ticket,
 * however often the button is pressed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreditExplanationApiTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4";
    private static final String ORDERS = BASE + "/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CreditDecisionRepository decisions;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private AgreementClient agreementClient;

    @MockitoBean
    private PromotionClient promotionClient;

    @MockitoBean
    private PartyClient partyClient;

    @MockitoBean
    private ReviewTicketClient tickets;

    @MockitoBean
    private PolicyClient policyClient;

    @BeforeEach
    void stubs() {
        reset(tickets, policyClient, partyClient);
        given(policyClient.evaluateOrder(any())).willReturn(PolicyClient.Decision.allow());
        given(catalogClient.findOffering(anyString()))
                .willReturn(Optional.of(new CatalogClient.OfferingRef("po-001", "Stub Offering")));
        given(tickets.open(any())).willReturn("tt-review-1");
    }

    @Test
    void aDecline_storesItsReasonAgainstTheOrder() throws Exception {
        String order = declinedOrderOf("cust-reason");

        CreditDecision row = decisions.findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc("genalpha", order)
                .orElseThrow();
        assertThat(row.getDecision()).isEqualTo("decline");
        // the mock bureau's decline carries band E and a payment remark
        assertThat(row.getReasonCode()).isEqualTo("PAYMENT_REMARK");
        assertThat(row.getReason()).contains("payment remark").contains("score band E");
    }

    @Test
    void anApproval_storesItsReasonToo() throws Exception {
        String order = orderAs("cust-approved");
        CreditDecision row = decisions.findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc("genalpha", order)
                .orElseThrow();
        assertThat(row.getReasonCode()).isEqualTo("SCORE_MEETS_THRESHOLD");
        assertThat(row.getReason()).isEqualTo("The credit bureau placed you in score band A, which meets what this plan needs.");
    }

    @Test
    void theOwnerReadsWhy_inPlainWords_andNobodyElseCan() throws Exception {
        String order = declinedOrderOf("cust-owner");

        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(customer("cust-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order))
                .andExpect(jsonPath("$.assessed").value(true))
                .andExpect(jsonPath("$.decision").value("decline"))
                .andExpect(jsonPath("$.reasonCode").value("PAYMENT_REMARK"))
                .andExpect(jsonPath("$.outcome").value("Your order is on hold until a person reviews it."))
                .andExpect(jsonPath("$.dataUsed[*].code").value(org.hamcrest.Matchers.contains(
                        "identity", "scoreBand", "paymentRemarks", "purpose")))
                .andExpect(jsonPath("$.humanReview").value(org.hamcrest.Matchers.containsString(
                        "never final without a way to a person")))
                .andExpect(jsonPath("$.contest.available").value(true))
                .andExpect(jsonPath("$.contest.requested").value(false))
                // categories, never the bureau's report
                .andExpect(jsonPath("$.report").doesNotExist());

        // another customer: the order does not exist for them
        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(customer("cust-stranger")))
                .andExpect(status().isNotFound());
        // staff may read it (unscoped, as everywhere in ordering)
        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(staff()))
                .andExpect(status().isOk());
    }

    @Test
    void askingForAPerson_filesExactlyOneTicket_perOrder() throws Exception {
        String order = declinedOrderOf("cust-contest");

        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-contest"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"The debt was paid in September.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.alreadyRequested").value(false))
                .andExpect(jsonPath("$.ticketId").value("tt-review-1"))
                .andExpect(jsonPath("$.nextSteps").value(org.hamcrest.Matchers.containsString("stays on hold")));

        // the second click is a replay, not a second ticket
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-contest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyRequested").value(true))
                .andExpect(jsonPath("$.ticketId").value("tt-review-1"));

        ArgumentCaptor<ReviewTicketClient.ReviewTicket> sent = ArgumentCaptor.forClass(ReviewTicketClient.ReviewTicket.class);
        verify(tickets, times(1)).open(sent.capture());
        ReviewTicketClient.ReviewTicket ticket = sent.getValue();
        assertThat(ticket.ticketType()).isEqualTo("creditReview");
        assertThat(ticket.relatedParty()).singleElement()
                .satisfies(p -> assertThat(p.id()).isEqualTo("cust-contest"));
        // the order, and the decision itself — the reference a retry finds this ticket by
        assertThat(ticket.relatedEntity()).extracting(ReviewTicketClient.EntityRef::referredType)
                .containsExactly("ProductOrder", "CreditDecision");
        assertThat(ticket.relatedEntity().get(0).id()).isEqualTo(order);
        assertThat(ticket.description()).contains("payment remark").contains("on hold");
        assertThat(ticket.note()).singleElement()
                .satisfies(n -> assertThat(n.text()).isEqualTo("The debt was paid in September."));

        // and the explanation now says it was asked for
        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(customer("cust-contest")))
                .andExpect(jsonPath("$.contest.requested").value(true))
                .andExpect(jsonPath("$.contest.ticketId").value("tt-review-1"));
    }

    @Test
    void anotherCustomer_cannotContestYourOrder() throws Exception {
        String order = declinedOrderOf("cust-victim");
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-intruder")))
                .andExpect(status().isNotFound());
        verify(tickets, never()).open(any());
    }

    @Test
    void anApproval_hasNothingToContest() throws Exception {
        String order = orderAs("cust-happy");
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-happy")))
                .andExpect(status().isBadRequest());
        verify(tickets, never()).open(any());
    }

    @Test
    void aTicketTheQueueDidNotTake_canBeAskedForAgain() throws Exception {
        String order = declinedOrderOf("cust-retry");
        given(tickets.open(any())).willThrow(new DownstreamException("care is down", null));
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-retry")))
                .andExpect(status().isBadGateway());

        // the failed attempt left no claim behind: the next one files
        reset(tickets);
        given(tickets.open(any())).willReturn("tt-review-2");
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-retry")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value("tt-review-2"));
    }

    @Test
    void theCheckoutNotice_saysWhoChecksAndWhatYourRightsAre() throws Exception {
        mockMvc.perform(get(BASE + "/creditDecision/notice").with(customer("cust-notice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checked").value(true))
                .andExpect(jsonPath("$.bureau").value("the credit bureau"))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString(
                        "a decline is never final without a way to a person")))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "reviews every decline"))))
                .andExpect(jsonPath("$.dataUsed.length()").value(4));
    }

    // ---- finding 3: a business admin reaches only its own organization's decisions ----

    @Test
    void aBusinessAdmin_cannotReadOrContest_anotherOrganizationsMember() throws Exception {
        String order = declinedOrderOf("member-of-b");
        given(partyClient.organizationOf("admin-of-a")).willReturn(Optional.of("org-a"));
        given(partyClient.organizationOf("member-of-b")).willReturn(Optional.of("org-b"));

        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(businessAdmin("admin-of-a")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(businessAdmin("admin-of-a")))
                .andExpect(status().isNotFound());
        String decision = decisions.findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc("genalpha", order)
                .orElseThrow().getId();
        mockMvc.perform(get(BASE + "/creditDecision/" + decision).with(businessAdmin("admin-of-a")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/creditDecision/" + decision + "/review").with(businessAdmin("admin-of-a")))
                .andExpect(status().isNotFound());
        verify(tickets, never()).open(any());
    }

    @Test
    void aBusinessAdmin_readsAndContests_itsOwnOrganizationsMember() throws Exception {
        String order = declinedOrderOf("member-of-a");
        given(partyClient.organizationOf("admin-of-a")).willReturn(Optional.of("org-a"));
        given(partyClient.organizationOf("member-of-a")).willReturn(Optional.of("org-a"));

        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(businessAdmin("admin-of-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("decline"));
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(businessAdmin("admin-of-a")))
                .andExpect(status().isCreated());
    }

    // ---- finding 4: a decline the operator's rule refuses stays explainable and contestable ----

    @Test
    void aDeclineTheOperatorsRuleRefuses_canStillBeExplainedAndPutToAPerson() throws Exception {
        given(policyClient.evaluateOrder(any())).willReturn(PolicyClient.Decision.deny(
                "We cannot offer this plan after a credit decline.", "no-postpaid-on-decline"));
        pin("cust-refused", "decline");

        MvcResult refused = mockMvc.perform(post(ORDERS).with(customer("cust-refused"))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POLICY_DENIED"))
                .andExpect(jsonPath("$.message").value("We cannot offer this plan after a credit decline."))
                .andExpect(jsonPath("$.creditDecision.id").isNotEmpty())
                .andReturn();
        String body = refused.getResponse().getContentAsString();
        String decision = com.jayway.jsonpath.JsonPath.read(body, "$.creditDecision.id");
        String href = com.jayway.jsonpath.JsonPath.read(body, "$.creditDecision.href");
        assertThat(href).isEqualTo(BASE + "/creditDecision/" + decision);

        mockMvc.perform(get(BASE + "/creditDecision/" + decision).with(customer("cust-refused")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionId").value(decision))
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.decision").value("decline"))
                .andExpect(jsonPath("$.outcomeCode").value("refusedByRule"))
                .andExpect(jsonPath("$.outcome").value(org.hamcrest.Matchers.containsString("own rules refused")))
                .andExpect(jsonPath("$.refusal").value("We cannot offer this plan after a credit decline."))
                .andExpect(jsonPath("$.show").value(true))
                .andExpect(jsonPath("$.contest.available").value(true));

        // nobody else's
        mockMvc.perform(get(BASE + "/creditDecision/" + decision).with(customer("cust-stranger")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/creditDecision/" + decision + "/review").with(customer("cust-stranger")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(BASE + "/creditDecision/" + decision + "/review").with(customer("cust-refused"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\": \"Paid last month.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionId").value(decision))
                .andExpect(jsonPath("$.ticketId").value("tt-review-1"))
                .andExpect(jsonPath("$.nextSteps").value(org.hamcrest.Matchers.containsString("was not placed")));
        ArgumentCaptor<ReviewTicketClient.ReviewTicket> sent = ArgumentCaptor.forClass(ReviewTicketClient.ReviewTicket.class);
        verify(tickets).open(sent.capture());
        assertThat(sent.getValue().relatedEntity()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(decision);
            assertThat(e.referredType()).isEqualTo("CreditDecision");
        });
        assertThat(sent.getValue().description()).contains("refused by the operator's rule")
                .contains("We cannot offer this plan after a credit decline.").doesNotContain("on hold");
    }

    @Test
    void aRefusalWithNothingToContest_pointsNowhere() throws Exception {
        given(policyClient.evaluateOrder(any())).willReturn(PolicyClient.Decision.deny("Max 2 SIMs.", "sim-cap"));
        mockMvc.perform(post(ORDERS).with(customer("cust-approved-refused"))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.creditDecision").doesNotExist());
    }

    // ---- finding 12: the server, not the shop, says when the explanation is shown ----

    @Test
    void theOrderSaysWhenItsCreditDecisionIsWorthExplaining() throws Exception {
        String declined = declinedOrderOf("cust-show-decline");
        mockMvc.perform(get(ORDERS + "/" + declined).with(customer("cust-show-decline")))
                .andExpect(jsonPath("$.creditDecision.href").value(ORDERS + "/" + declined + "/creditDecision"));
        // care cancels it: the explanation does not disappear with the hold
        mockMvc.perform(patch(ORDERS + "/" + declined).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"cancelled\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(ORDERS).with(customer("cust-show-decline")))
                .andExpect(jsonPath("$[0].id").value(declined))
                .andExpect(jsonPath("$[0].creditDecision.href").value(ORDERS + "/" + declined + "/creditDecision"));

        // a borderline answer that let the order through is contestable, so it is shown too
        pin("cust-show-review", "review");
        String review = orderAs("cust-show-review");
        mockMvc.perform(get(ORDERS + "/" + review).with(customer("cust-show-review")))
                .andExpect(jsonPath("$.state").value(org.hamcrest.Matchers.not("held")))
                .andExpect(jsonPath("$.creditDecision.href").exists());
        mockMvc.perform(get(ORDERS + "/" + review + "/creditDecision").with(customer("cust-show-review")))
                .andExpect(jsonPath("$.show").value(true));

        // an approval has nothing to show
        String approved = orderAs("cust-show-approve");
        mockMvc.perform(get(ORDERS + "/" + approved).with(customer("cust-show-approve")))
                .andExpect(jsonPath("$.creditDecision").doesNotExist());
        mockMvc.perform(get(ORDERS + "/" + approved + "/creditDecision").with(customer("cust-show-approve")))
                .andExpect(jsonPath("$.show").value(false));
    }

    // ---- finding 13: the outcome is what happened to the order, not what the bureau said ----

    @Test
    void aReleasedDecline_saysItWentAhead_andItsTicketDoesNotClaimAHold() throws Exception {
        String order = declinedOrderOf("cust-released");
        mockMvc.perform(patch(ORDERS + "/" + order).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"acknowledged\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(customer("cust-released")))
                .andExpect(jsonPath("$.outcomeCode").value("released"))
                .andExpect(jsonPath("$.outcome").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("on hold"))));
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-released")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nextSteps").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("stays on hold"))));
        ArgumentCaptor<ReviewTicketClient.ReviewTicket> sent = ArgumentCaptor.forClass(ReviewTicketClient.ReviewTicket.class);
        verify(tickets).open(sent.capture());
        assertThat(sent.getValue().description()).doesNotContain("on hold until a person decides")
                .contains("already went ahead");
    }

    @Test
    void aCancelledDecline_saysItWasCancelled() throws Exception {
        String order = declinedOrderOf("cust-cancelled");
        mockMvc.perform(patch(ORDERS + "/" + order).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"cancelled\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(customer("cust-cancelled")))
                .andExpect(jsonPath("$.outcomeCode").value("cancelled"))
                .andExpect(jsonPath("$.outcome").value("Your order was cancelled."));
    }

    @Test
    void aBorderlineAnswerHeldByTheOperator_saysItIsOnHold_notThatItWentAhead() throws Exception {
        pin("cust-review-held", "review");
        String order = orderAs("cust-review-held");
        mockMvc.perform(patch(ORDERS + "/" + order).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\": \"held\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(ORDERS + "/" + order + "/creditDecision").with(customer("cust-review-held")))
                .andExpect(jsonPath("$.outcomeCode").value("held"))
                .andExpect(jsonPath("$.outcome").value("Your order is on hold until a person reviews it."));
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-review-held")))
                .andExpect(status().isCreated());
        ArgumentCaptor<ReviewTicketClient.ReviewTicket> sent = ArgumentCaptor.forClass(ReviewTicketClient.ReviewTicket.class);
        verify(tickets).open(sent.capture());
        assertThat(sent.getValue().description()).contains("on hold until a person decides")
                .doesNotContain("was not held");
    }

    // ---- finding 14: the claim is committed before the ticket call, and a dead claim is taken over ----

    @Test
    void aRequestStillWithTheCareQueue_isNotFiledAgain_butADeadOneIsTakenOver() throws Exception {
        String order = declinedOrderOf("cust-in-flight");
        CreditDecision row = decisions.findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc("genalpha", order)
                .orElseThrow();
        // a first request claimed it a moment ago and is still waiting on the care queue
        row.setReviewRequestedAt(java.time.OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        decisions.save(row);
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-in-flight")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyRequested").value(true))
                .andExpect(jsonPath("$.ticketId").doesNotExist());
        verify(tickets, never()).open(any());

        // that attempt died without a ticket and without giving the claim back: after a while, take it over
        row.setReviewRequestedAt(java.time.OffsetDateTime.now().minusMinutes(5).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        decisions.save(row);
        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-in-flight")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value("tt-review-1"));
        verify(tickets, times(1)).open(any());
    }

    private void pin(String party, String decision) throws Exception {
        mockMvc.perform(post(BASE + "/creditDecision/mockOutcome").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\": \"" + party + "\", \"decision\": \"" + decision + "\"}"))
                .andExpect(status().isOk());
    }

    private static final String ORDER_BODY = """
            {"productOrderItem": [{"id": "1", "action": "add",
              "productOffering": {"id": "po-001", "name": "Stub Offering"}}]}
            """;

    private static RequestPostProcessor businessAdmin(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("business:admin"),
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private String declinedOrderOf(String party) throws Exception {
        mockMvc.perform(post(BASE + "/creditDecision/mockOutcome").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\": \"" + party + "\", \"decision\": \"decline\"}"))
                .andExpect(status().isOk());
        return orderAs(party);
    }

    private String orderAs(String party) throws Exception {
        MvcResult result = mockMvc.perform(post(ORDERS).with(customer(party))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add",
                                  "productOffering": {"id": "po-001", "name": "Stub Offering"}}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ordering:read"),
                new SimpleGrantedAuthority("ordering:write"));
    }
}
