package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.PromotionClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.events.OutboxEventRepository;
import com.bss.ordering.events.OutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The transactional-outbox guarantees: an event row commits with the business
 * change (and only with it), the relay drains rows to Kafka in order, and a
 * broker failure retains rows for retry instead of losing events.
 */
@SpringBootTest(properties = {
        "bss.events.enabled=true",
        // effectively disable the schedule; tests drive the relay by hand
        "bss.events.relay-interval-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OutboxTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxRelay relay;

    @MockBean
    private KafkaTemplate<String, Object> eventKafkaTemplate;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private AgreementClient agreementClient;

    @MockBean
    private PromotionClient promotionClient;

    @MockBean
    private PartyClient partyClient;

    @MockBean
    private InventoryClient inventoryClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void drainOutbox() {
        outbox.deleteAll();
    }

    @Test
    void creatingAnOrder_writesTheEventRowInTheSameTransaction() throws Exception {
        createOrder();
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findTop100ByOrderByCreatedAtAsc().get(0).getEventType())
                .isEqualTo("ProductOrderCreateEvent");
    }

    @Test
    void rejectedOrder_leavesNoEventRow() throws Exception {
        given(catalogClient.findOffering("missing")).willReturn(Optional.empty());
        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "productOfferingId": "missing"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(outbox.count()).isZero();
    }

    @Test
    void relay_deliversAndDrains() throws Exception {
        given(eventKafkaTemplate.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        createOrder();

        relay.flush();

        verify(eventKafkaTemplate).send(eq("bss.ordering.events"), anyString(), any());
        assertThat(outbox.count()).isZero();
    }

    @Test
    void relay_keepsTheRowWhenTheBrokerFails() throws Exception {
        given(eventKafkaTemplate.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        createOrder();

        relay.flush();

        assertThat(outbox.count()).isEqualTo(1);

        // broker recovers: next tick delivers the retained event
        given(eventKafkaTemplate.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        relay.flush();
        assertThat(outbox.count()).isZero();
    }

    private void createOrder() throws Exception {
        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOrderItem": [{"id": "1", "action": "add"}], "description": "outbox test"}
                                """))
                .andExpect(status().isCreated());
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
