package com.bss.ordering;

import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.InventoryClient;
import com.bss.ordering.client.PartyClient;
import com.bss.ordering.events.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies TMF688 events are emitted at the publisher boundary for every order
 * mutation, including the state-change distinction. Actual Kafka delivery is
 * covered by OrderEventsKafkaIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventPublishingTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DomainEventPublisher events;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private PartyClient partyClient;

    @MockBean
    private InventoryClient inventoryClient;

    @Test
    void create_publishesCreateEvent() throws Exception {
        String id = create();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("ProductOrderCreateEvent"), eq("productOrder"), payload.capture());
        assertThat(objectMapper.valueToTree(payload.getValue()).get("id").asText()).isEqualTo(id);
    }

    @Test
    void nonStatePatch_publishesAttributeValueChangeEvent() throws Exception {
        String id = create();
        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "updated by event test"}
                                """))
                .andExpect(status().isOk());
        verify(events).publish(eq("ProductOrderAttributeValueChangeEvent"), eq("productOrder"), any());
    }

    @Test
    void stateTransition_publishesStateChangeEvent() throws Exception {
        String id = create();
        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state": "completed"}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("ProductOrderStateChangeEvent"), eq("productOrder"), payload.capture());
        assertThat(objectMapper.valueToTree(payload.getValue()).get("state").asText()).isEqualTo("completed");
    }

    @Test
    void delete_publishesDeleteEvent() throws Exception {
        String id = create();
        mockMvc.perform(delete(BASE + "/" + id).with(writeToken()))
                .andExpect(status().isNoContent());
        verify(events).publish(eq("ProductOrderDeleteEvent"), eq("productOrder"), any());
    }

    private String create() throws Exception {
        String response = mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state": "acknowledged"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
