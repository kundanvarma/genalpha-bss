package com.bss.campaign;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
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

    /** The console passes the copy assistant's provenance with the message;
     * the campaign answers it back from the store, and human copy shows none. */
    @Test
    void aiWrittenMessageIsMarkedOnTheWire() throws Exception {
        mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "AI drafted", "triggerEventType": "ProductOrderCreateEvent",
                                 "message": {"subject": "Hi", "content": "Edited after the draft.",
                                             "aiGenerated": true, "aiModel": "anthropic/claude-sonnet"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.aiGenerated").value(true))
                .andExpect(jsonPath("$.message.aiModel").value("anthropic/claude-sonnet"));
        mockMvc.perform(get(BASE + "/campaign").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'AI drafted')].message.aiGenerated").value(true))
                .andExpect(jsonPath("$[?(@.name == 'AI drafted')].message.aiModel")
                        .value("anthropic/claude-sonnet"));

        mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "Typed by hand", "triggerEventType": "ProductOrderCreateEvent",
                                 "message": {"subject": "Hi", "content": "Mine."}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.aiGenerated").doesNotExist())
                .andExpect(jsonPath("$.message.aiModel").doesNotExist());
    }

    /** Finding 10: the server sets the mark from the receipt; a body cannot clear it. */
    @Test
    void aDraftReceiptMarksTheCampaignAndTheBodyCannotClearIt() throws Exception {
        mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "Drafted, flag cleared", "triggerEventType": "ProductOrderCreateEvent",
                                 "message": {"subject": "Hi", "content": "Edited after the draft.",
                                             "aiGenerated": false, "aiModel": "",
                                             "aiDraft": "%s"}}
                                """.formatted(com.bss.campaign.service.AiProvenanceTest.VECTOR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.aiGenerated").value(true))
                .andExpect(jsonPath("$.message.aiModel").value("anthropic/claude-sonnet-4"));
        mockMvc.perform(post(BASE + "/campaign").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "Forged receipt", "triggerEventType": "ProductOrderCreateEvent",
                                 "message": {"subject": "Hi", "content": "x", "aiDraft": "aid1.e30.forged"}}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Findings 10 and 20: every piece of journey copy with a receipt is marked on the server, branches too. */
    @Test
    void aJourneysDraftedCopyIsMarkedOnTheServerAndStaysMarkedThroughAnEdit() throws Exception {
        String steps = """
                [{"type":"message","subject":"Hi","content":"Welcome","aiGenerated":false,"aiDraft":"%1$s"},
                 {"type":"decision","inSegment":"Gamers",
                  "then":{"subject":"Play","content":"On","aiDraft":"%1$s"},
                  "else":{"subject":"Plain","content":"Typed by a person"}}]
                """.formatted(com.bss.campaign.service.AiProvenanceTest.VECTOR);
        String id = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(post(BASE + "/journey").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Drafted journey\",\"triggerEventType\":\"X\",\"steps\":" + steps + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.steps[0].aiGenerated").value(true))
                .andExpect(jsonPath("$.steps[0].aiModel").value("anthropic/claude-sonnet-4"))
                .andExpect(jsonPath("$.steps[1].then.aiGenerated").value(true))
                .andExpect(jsonPath("$.steps[1].then.aiModel").value("anthropic/claude-sonnet-4"))
                .andExpect(jsonPath("$.steps[1].else.aiGenerated").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // an edit that clears the flag but keeps the receipt keeps the mark
        mockMvc.perform(patch(BASE + "/journey/" + id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"steps\":" + steps.replace("\"Welcome\"", "\"Welcome, edited\"") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].content").value("Welcome, edited"))
                .andExpect(jsonPath("$.steps[0].aiGenerated").value(true));
        mockMvc.perform(post(BASE + "/journey").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forged\",\"triggerEventType\":\"X\",\"steps\":"
                                + "[{\"type\":\"message\",\"subject\":\"s\",\"content\":\"c\",\"aiDraft\":\"aid1.e30.forged\"}]}"))
                .andExpect(status().isBadRequest());
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
