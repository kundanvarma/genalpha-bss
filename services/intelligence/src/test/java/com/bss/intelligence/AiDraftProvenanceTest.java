package com.bss.intelligence;

import com.bss.intelligence.service.AiDraftProvenance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EU AI Act Art. 50(2), provenance on the server (#422 review, findings 5 and 10):
 * every piece of copy a model drafts leaves intelligence with a signed receipt
 * — {tenant, model, draft id, issued at} under the operator's provenance key —
 * so the campaign component can set the mark itself instead of trusting a flag
 * in the request body. The copilot's proposal arrives already marked, so a
 * console that posts it straight to /campaign or /journey sends marked copy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiDraftProvenanceTest {

    private static final String BASE = "/ai/v1";

    /**
     * The cross-module contract: campaign's {@code AiProvenanceTest} verifies this
     * exact string with the same key. If either side changes the format, one of the
     * two tests goes red. The value was computed outside Java (Python's hmac over
     * "aid1." + the base64url payload), so it is not this class checking itself.
     */
    static final String VECTOR = "aid1.eyJ0ZW5hbnQiOiJnZW5hbHBoYSIsIm1vZGVsIjoiYW50aHJvcGljL2NsYXVkZS1zb25uZXQtNCIs"
            + "ImRyYWZ0IjoiZHJhZnQtMSIsImlzc3VlZEF0IjoiMjAyNi0xMS0wM1QxMDowMDowMFoifQ."
            + "mn0XNb3gS8VIBsPAVQfMQgsdU4q43KmBkRAlu03l138";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ai:use"));
    }

    private JsonNode payloadOf(String token) {
        assertThat(token).startsWith("aid1.");
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "aid1.<payload>.<signature>");
        return json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    @Test
    void theReceiptIsAFixedFormatBothSidesAgreeOn() {
        String token = AiDraftProvenance.sign("test-provenance-key".getBytes(StandardCharsets.UTF_8),
                "genalpha", "anthropic/claude-sonnet-4", "draft-1", "2026-11-03T10:00:00Z");
        assertEquals(VECTOR, token);
    }

    @Test
    void campaignCopyComesWithAReceiptForThisTenantAndModel() throws Exception {
        JsonNode copy = json.readTree(mockMvc.perform(post(BASE + "/campaignCopy").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"brief\": \"welcome new customers\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode payload = payloadOf(copy.path("provenance").asString());
        assertEquals("genalpha", payload.path("tenant").asString());
        assertEquals("stub/deterministic-template", payload.path("model").asString());
        assertThat(payload.path("draft").asString()).isNotBlank();
    }

    @Test
    void everyDraftedJourneyMessageIsMarkedAndAWaitIsNot() throws Exception {
        JsonNode draft = json.readTree(mockMvc.perform(post(BASE + "/journeyDraft").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"brief\": \"onboard new fibre customers\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        int messages = 0;
        for (JsonNode step : draft.path("steps")) {
            if ("message".equals(step.path("type").asString())) {
                messages++;
                assertEquals(true, step.path("aiGenerated").asBoolean());
                assertEquals("stub/deterministic-template", step.path("aiModel").asString());
                assertEquals("genalpha", payloadOf(step.path("aiDraft").asString()).path("tenant").asString());
            } else {
                assertThat(step.has("aiGenerated") || step.has("aiDraft")).isFalse();
            }
        }
        assertThat(messages).isPositive();
    }

    /** Finding 5: the Growth desk posts the proposal straight to the campaign API. */
    @Test
    void aCopilotCampaignProposalArrivesMarked() throws Exception {
        JsonNode reply = json.readTree(mockMvc.perform(post(BASE + "/journeyCopilot").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"owner\",\"content\":\"win back customers at risk of churn\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode message = reply.path("proposal").path("campaign").path("message");
        assertEquals("A little thank-you from us", message.path("subject").asString());
        assertEquals(true, message.path("aiGenerated").asBoolean());
        assertEquals("stub/deterministic-template", message.path("aiModel").asString());
        assertEquals("stub/deterministic-template", payloadOf(message.path("aiDraft").asString()).path("model").asString());
    }

    @Test
    void aCopilotJourneyProposalMarksEveryPieceOfCopyIncludingBranches() throws Exception {
        String proposal = """
                {"artifact":"journey","journey":{"name":"j","steps":[
                  {"type":"message","subject":"Hi","content":"Welcome"},
                  {"type":"wait","days":3},
                  {"type":"waitForEvent","event":"X","days":2,"onTimeout":{"subject":"Still there?","content":"Nudge"}},
                  {"type":"decision","inSegment":"Gamers","then":{"subject":"Play","content":"On"},"else":{"subject":"Plain","content":"Off"}},
                  {"type":"exit"}]}}
                """;
        JsonNode node = json.readTree(proposal);
        AiDraftProvenance.markProposal(node, "openai/gpt-5", "aid1.x.y");
        JsonNode steps = node.path("journey").path("steps");
        for (JsonNode copy : new JsonNode[] {steps.get(0), steps.get(2).path("onTimeout"),
                steps.get(3).path("then"), steps.get(3).path("else")}) {
            assertEquals(true, copy.path("aiGenerated").asBoolean(), copy.toString());
            assertEquals("openai/gpt-5", copy.path("aiModel").asString());
            assertEquals("aid1.x.y", copy.path("aiDraft").asString());
        }
        assertThat(steps.get(1).has("aiGenerated")).isFalse();
        assertThat(steps.get(4).has("aiGenerated")).isFalse();
    }

    @Test
    void withNoKeyNoReceiptIsIssuedButTheMarkStillTravels() {
        AiDraftProvenance off = new AiDraftProvenance("", null);
        assertNull(off.issue("stub", "m"));
        JsonNode node = json.readTree("{\"artifact\":\"campaign\",\"campaign\":{\"message\":{\"subject\":\"s\",\"content\":\"c\"}}}");
        AiDraftProvenance.markProposal(node, "stub/m", null);
        JsonNode message = node.path("campaign").path("message");
        assertEquals(true, message.path("aiGenerated").asBoolean());
        assertEquals("stub/m", message.path("aiModel").asString());
        assertThat(message.has("aiDraft")).isFalse();
    }
}
