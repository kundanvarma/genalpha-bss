package com.bss.party;

import com.bss.party.events.DomainEventPublisher;
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
 * Verifies TMF688 events are emitted at the publisher boundary for every
 * mutation. Actual Kafka delivery is covered by the ordering service's
 * Testcontainers integration test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventPublishingTest {

    private static final String BASE = "/tmf-api/party/v4/individual";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DomainEventPublisher events;

    @Test
    void create_publishesCreateEvent() throws Exception {
        String id = create();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("IndividualCreateEvent"), eq("individual"), payload.capture());
        assertThat(objectMapper.valueToTree(payload.getValue()).get("id").asText()).isEqualTo(id);
    }

    @Test
    void patch_publishesAttributeValueChangeEvent() throws Exception {
        String id = create();
        mockMvc.perform(patch(BASE + "/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"givenName": "Updated"}
                                """))
                .andExpect(status().isOk());
        verify(events).publish(eq("IndividualAttributeValueChangeEvent"), eq("individual"), any());
    }

    @Test
    void delete_publishesDeleteEvent() throws Exception {
        String id = create();
        mockMvc.perform(delete(BASE + "/" + id).with(writeToken()))
                .andExpect(status().isNoContent());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("IndividualDeleteEvent"), eq("individual"), payload.capture());
        assertThat(objectMapper.valueToTree(payload.getValue()).get("id").asText()).isEqualTo(id);
    }

    private String create() throws Exception {
        String response = mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"familyName": "Probe"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("party:write"));
    }
}
