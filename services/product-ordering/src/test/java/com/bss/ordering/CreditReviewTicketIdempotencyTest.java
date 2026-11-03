package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.client.PromotionClient;
import com.bss.ordering.entity.CreditDecision;
import com.bss.ordering.repository.CreditDecisionRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finding 14 of the #422 review: a slow care queue must never get two review
 * tickets for one decision. The stand-in here is a real HTTP trouble-ticket
 * service that CREATES the ticket and then answers too late — ordering's read
 * timeout fires after the ticket exists. The customer is told to try again;
 * the retry must find that ticket (by the decision it names) instead of filing
 * a second one, and no database lock may be held across the slow call.
 */
@SpringBootTest(properties = "spring.http.clients.read-timeout=400ms")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreditReviewTicketIdempotencyTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4";
    private static final String ORDERS = BASE + "/productOrder";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** What the stand-in care queue holds: every ticket it ever created. */
    private static final List<ObjectNode> FILED = new CopyOnWriteArrayList<>();
    private static volatile long answerDelayMillis;
    private static HttpServer standIn;

    @DynamicPropertySource
    static void ticketService(DynamicPropertyRegistry registry) throws IOException {
        if (standIn == null) {
            standIn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            standIn.setExecutor(Executors.newCachedThreadPool());
            standIn.createContext("/tmf-api/troubleTicket/v4/troubleTicket", CreditReviewTicketIdempotencyTest::serve);
            standIn.start();
        }
        registry.add("bss.downstream.ticket-base-url",
                () -> "http://127.0.0.1:" + standIn.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        if (standIn != null) {
            standIn.stop(0);
        }
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("POST".equals(exchange.getRequestMethod())) {
                ObjectNode ticket = (ObjectNode) JSON.readTree(exchange.getRequestBody().readAllBytes());
                ticket.put("id", "tt-" + UUID.randomUUID());
                FILED.add(ticket); // created FIRST ...
                if (answerDelayMillis > 0) {
                    try {
                        Thread.sleep(answerDelayMillis); // ... then answered too late
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                answer(exchange, 201, JSON.writeValueAsBytes(ticket));
                return;
            }
            // GET: the TMF621 list, newest first, as trouble-ticket answers it
            ArrayNode list = JSON.createArrayNode();
            for (int i = FILED.size() - 1; i >= 0; i--) {
                list.add(FILED.get(i));
            }
            answer(exchange, 200, JSON.writeValueAsBytes(list));
        }
    }

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException gone) {
            // ordering stopped listening (its read timeout) — the ticket exists anyway
        }
    }

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

    @BeforeEach
    void stubs() {
        FILED.clear();
        answerDelayMillis = 0;
        given(catalogClient.findOffering(anyString()))
                .willReturn(Optional.of(new CatalogClient.OfferingRef("po-001", "Stub Offering")));
    }

    @Test
    void aTicketCreatedButAnsweredTooLate_isFoundOnTheRetry_notFiledTwice() throws Exception {
        String order = declinedOrderOf("cust-slow-care");
        String review = ORDERS + "/" + order + "/creditDecision/review";

        answerDelayMillis = 1500;
        mockMvc.perform(post(review).with(customer("cust-slow-care")))
                .andExpect(status().isBadGateway());
        assertThat(FILED).as("the care queue took the ticket before the answer was lost").hasSize(1);

        answerDelayMillis = 0;
        mockMvc.perform(post(review).with(customer("cust-slow-care")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value(FILED.get(0).get("id").asString()));
        assertThat(FILED).as("one decision, one ticket").hasSize(1);

        // and a third press is a plain replay
        mockMvc.perform(post(review).with(customer("cust-slow-care")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyRequested").value(true));
        assertThat(FILED).hasSize(1);

        CreditDecision row = decisions.findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc("genalpha", order)
                .orElseThrow();
        assertThat(row.getReviewTicketId()).isEqualTo(FILED.get(0).get("id").asString());
        // the ticket names the decision it is for — the reference the retry looked it up by
        JsonNode refs = FILED.get(0).get("relatedEntity");
        assertThat(refs.toString()).contains(row.getId()).contains("CreditDecision");
    }

    @Test
    void anotherCustomersTicketForTheSameOrderShape_isNotMistakenForOurs() throws Exception {
        String order = declinedOrderOf("cust-own-ticket");
        // a ticket already in the queue that names a DIFFERENT decision
        ObjectNode other = JSON.createObjectNode();
        other.put("id", "tt-someone-else");
        ArrayNode refs = other.putArray("relatedEntity");
        refs.addObject().put("id", "another-decision").put("@referredType", "CreditDecision");
        FILED.add(other);

        mockMvc.perform(post(ORDERS + "/" + order + "/creditDecision/review").with(customer("cust-own-ticket")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value(org.hamcrest.Matchers.not("tt-someone-else")));
        assertThat(FILED).hasSize(2);
    }

    private String declinedOrderOf(String party) throws Exception {
        mockMvc.perform(post(BASE + "/creditDecision/mockOutcome").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\": \"" + party + "\", \"decision\": \"decline\"}"))
                .andExpect(status().isOk());
        MvcResult result = mockMvc.perform(post(ORDERS).with(customer(party))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add",
                                  "productOffering": {"id": "po-001", "name": "Stub Offering"}}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
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
