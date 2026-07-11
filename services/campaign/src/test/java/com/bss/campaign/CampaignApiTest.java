package com.bss.campaign;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.bss.campaign.client.CommunicationClient;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampaignApiTest {

    private static final String BASE = "/tmf-api/campaignManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommunicationClient communicationClient;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("campaign:read"),
                new SimpleGrantedAuthority("campaign:write"));
    }

    @Test
    void campaignLifecycle() throws Exception {
        String body = """
                {"name": "Welcome journey", "triggerEventType": "ProductOrderCreateEvent",
                 "promotionCode": "WELCOME10",
                 "message": {"subject": "Welcome!", "content": "Use {code} on your next order."}}
                """;
        String id = mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.href").isNotEmpty())
                .andExpect(jsonPath("$.message.subject").value("Welcome!"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get(BASE + "/campaign").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Welcome journey"));

        mockMvc.perform(patch(BASE + "/campaign/" + id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"paused\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"));

        mockMvc.perform(get(BASE + "/campaign/" + id + "/execution").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsIncompleteAndUnknownStatus() throws Exception {
        mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"No trigger\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bad status", "status": "launched",
                                 "triggerEventType": "X",
                                 "message": {"subject": "s", "content": "c"}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campaignsAreBackOfficeOnly() throws Exception {
        mockMvc.perform(get(BASE + "/campaign")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/campaign")
                        .with(jwt().authorities(new SimpleGrantedAuthority("campaign:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
