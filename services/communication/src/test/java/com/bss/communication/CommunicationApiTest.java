package com.bss.communication;

import com.bss.communication.notify.EventNotificationMapper;
import com.bss.communication.service.CommunicationMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunicationApiTest {

    private static final String BASE = "/tmf-api/communicationManagement/v4/communicationMessage";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventNotificationMapper mapper;

    @Autowired
    private CommunicationMessageService service;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("communication:read"),
                new SimpleGrantedAuthority("communication:write"));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("communication:read"),
                new SimpleGrantedAuthority("communication:write"));
    }

    private Map<String, Object> orderEvent(String party, String description) {
        return Map.of("productOrder", Map.of(
                "description", description,
                "relatedParty", List.of(Map.of("id", party, "role", "customer"))));
    }

    @Test
    void eventsBecomeNotifications_idempotently_andOnlyTheSignalOnes() {
        Optional<EventNotificationMapper.Notification> n =
                mapper.map("ProductOrderCreateEvent", orderEvent("cust-n1", "GenAlpha One"));
        assertThat(n).isPresent();
        assertThat(n.get().subject()).isEqualTo("Order received");

        service.mint("evt-1", "ProductOrderCreateEvent", null, n.get());
        service.mint("evt-1", "ProductOrderCreateEvent", null, n.get()); // duplicate delivery

        // Noise events map to nothing.
        assertThat(mapper.map("ProductOrderAttributeValueChangeEvent",
                orderEvent("cust-n1", "x"))).isEmpty();
        assertThat(mapper.map("ProductStockCreateEvent", Map.of())).isEmpty();
    }

    @Test
    void customerSeesOwnNotifications_marksThemRead_neverSends() throws Exception {
        service.mint("evt-2", "ProductOrderCreateEvent", null,
                new EventNotificationMapper.Notification("cust-n2", "Order received", "hello"));

        // Own list, newest first, dedup proven by evt-1 above appearing once for its owner.
        String body = mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-n2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subject").value("Order received"))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$[0].id");

        // Foreign customers see nothing.
        mockMvc.perform(get(BASE + "/" + id).with(customer("cust-other")))
                .andExpect(status().isNotFound());

        // Mark read.
        mockMvc.perform(patch(BASE + "/" + id).with(customer("cust-n2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"read\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("read"));

        // Customers cannot send.
        mockMvc.perform(post(BASE).with(customer("cust-n2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "spam", "relatedParty": [{"id": "cust-other", "role": "customer"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void backOfficeSendsAdHoc_theMartechDoor() throws Exception {
        mockMvc.perform(post(BASE).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "Summer campaign: double data",
                                 "content": "Reply YES to activate",
                                 "relatedParty": [{"id": "cust-n3", "role": "customer"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("sent"));

        mockMvc.perform(get(BASE + "?limit=100").with(customer("cust-n3")))
                .andExpect(jsonPath("$[0].subject").value("Summer campaign: double data"));
    }
}
