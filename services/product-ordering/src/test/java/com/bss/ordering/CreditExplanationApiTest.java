package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.PartyClient;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credit explanations (#422): every decision keeps WHY (a code and a sentence)
 * against the order it was taken for; the order's owner — and nobody else —
 * can read it, with the data categories used and that a person reviews every
 * decline; and asking a person to look again files exactly one ticket per
 * order, however often the button is pressed.
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

    @BeforeEach
    void stubs() {
        reset(tickets);
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
                        "A person reviews every credit decline")))
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
        assertThat(ticket.relatedEntity()).singleElement()
                .satisfies(e -> assertThat(e.id()).isEqualTo(order));
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
                        "a person reviews every decline")))
                .andExpect(jsonPath("$.dataUsed.length()").value(4));
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
