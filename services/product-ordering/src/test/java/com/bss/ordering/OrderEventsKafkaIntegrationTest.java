package com.bss.ordering;

import com.bss.ordering.client.AgreementClient;
import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end event delivery against a real Kafka broker: creating an order
 * must put a TMF688 ProductOrderCreateEvent envelope on the wire, after
 * commit, serialized as JSON. Skipped without Docker; runs in CI.
 */
@SpringBootTest(properties = "bss.events.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OrderEventsKafkaIntegrationTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";
    private static final String TOPIC = "bss.ordering.events";

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.4"));

    // Deliberately a property, not @ServiceConnection: EventConfig builds its
    // producer factory from KafkaProperties, and connection details injected via
    // @ServiceConnection bypass KafkaProperties — the publisher would silently
    // send to the yml default instead of the container.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private AgreementClient agreementClient;

    @MockBean
    private PartyClient partyClient;

    @MockBean
    private InventoryClient inventoryClient;

    @Test
    void creatingAnOrder_putsATmfEnvelopeOnTheTopic() throws Exception {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));

            String response = mockMvc.perform(post(BASE)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ordering:write")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productOrderItem": [{"id": "1", "action": "add"}], "state": "acknowledged", "description": "kafka wire test"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String orderId = objectMapper.readTree(response).get("id").asText();

            ConsumerRecord<String, String> record = pollForOrder(consumer, orderId);
            assertThat(record).as("event for order %s on %s", orderId, TOPIC).isNotNull();

            JsonNode envelope = objectMapper.readTree(record.value());
            assertThat(envelope.get("eventType").asText()).isEqualTo("ProductOrderCreateEvent");
            assertThat(envelope.get("eventId").asText()).isNotBlank();
            assertThat(envelope.get("eventTime").asText()).isNotBlank();
            assertThat(envelope.at("/event/productOrder/id").asText()).isEqualTo(orderId);
            assertThat(envelope.at("/event/productOrder/state").asText()).isEqualTo("acknowledged");
            assertThat(record.key()).isEqualTo(envelope.get("eventId").asText());
        }
    }

    private ConsumerRecord<String, String> pollForOrder(KafkaConsumer<String, String> consumer, String orderId) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value().contains(orderId)) {
                    return record;
                }
            }
        }
        return null;
    }

    private KafkaConsumer<String, String> newConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }
}
