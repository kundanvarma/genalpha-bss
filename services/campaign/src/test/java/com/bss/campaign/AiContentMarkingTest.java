package com.bss.campaign;

import com.bss.campaign.client.CommunicationClient;
import com.bss.campaign.client.MachineTokenInterceptor;
import com.bss.campaign.client.RestCommunicationClient;
import com.bss.campaign.service.JourneyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * EU AI Act Art. 50(2): copy a model wrote reaches communication with a
 * machine-readable mark BESIDE the TMF681 payload — characteristics
 * {@code aiGenerated=true} and {@code aiModel} — and the standard fields are
 * exactly what they were. Human copy carries no mark at all.
 */
class AiContentMarkingTest {

    private static final String URL = "http://comm.test/tmf-api/communicationManagement/v4/communicationMessage";

    private static MachineTokenInterceptor passThrough() throws Exception {
        MachineTokenInterceptor interceptor = mock(MachineTokenInterceptor.class);
        when(interceptor.intercept(any(), any(), any())).thenAnswer(inv -> inv
                .getArgument(2, ClientHttpRequestExecution.class)
                .execute(inv.getArgument(0), inv.getArgument(1)));
        return interceptor;
    }

    @Test
    void aiCopyCarriesTheMarkBesideTheStandardPayload() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCommunicationClient client = new RestCommunicationClient(builder, passThrough(), "http://comm.test");

        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.subject").value("Welcome aboard"))
                .andExpect(jsonPath("$.content").value("Glad to have you."))
                .andExpect(jsonPath("$.messageType").value("inApp"))
                .andExpect(jsonPath("$.relatedParty[0].id").value("party-1"))
                .andExpect(jsonPath("$.characteristic[0].name").value("aiGenerated"))
                .andExpect(jsonPath("$.characteristic[0].value").value(true))
                .andExpect(jsonPath("$.characteristic[1].name").value("aiModel"))
                .andExpect(jsonPath("$.characteristic[1].value").value("anthropic/claude-sonnet"))
                .andRespond(withSuccess("{\"status\":\"sent\"}", MediaType.APPLICATION_JSON));

        CommunicationClient.SendOutcome outcome = client.send("party-1", "Welcome aboard", "Glad to have you.",
                null, Map.of("source", "AI welcome"), new CommunicationClient.AiMark("anthropic/claude-sonnet"));
        assertEquals(CommunicationClient.SendOutcome.SENT, outcome);
        server.verify();
    }

    @Test
    void aTransactionalAiNoticeKeepsBothCharacteristics() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCommunicationClient client = new RestCommunicationClient(builder, passThrough(), "http://comm.test");

        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.characteristic[0].name").value("category"))
                .andExpect(jsonPath("$.characteristic[0].value").value("transactional"))
                .andExpect(jsonPath("$.characteristic[1].name").value("aiGenerated"))
                .andExpect(jsonPath("$.characteristic.length()").value(2))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.send("party-1", "Running low", "You have 1 GB left.", "sms",
                Map.of("category", "transactional"), new CommunicationClient.AiMark(null));
        server.verify();
    }

    @Test
    void humanCopyCarriesNoMark() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCommunicationClient client = new RestCommunicationClient(builder, passThrough(), "http://comm.test");

        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.characteristic").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("aiGenerated"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.send("party-1", "Hello", "Written by a person.", Map.of("source", "Human note"));
        server.verify();
    }

    @Test
    void aJourneyStepTheCopilotDraftedIsMarkedAndAPlainStepIsNot() {
        assertEquals(new CommunicationClient.AiMark("openai/gpt-5"),
                JourneyService.aiMarkOf(Map.of("type", "message", "subject", "Hi", "content", "x",
                        "aiGenerated", true, "aiModel", "openai/gpt-5")));
        assertEquals(new CommunicationClient.AiMark(null),
                JourneyService.aiMarkOf(Map.of("type", "message", "aiGenerated", "true")));
        assertNull(JourneyService.aiMarkOf(Map.of("type", "message", "subject", "Hi", "content", "x")));
        assertNull(JourneyService.aiMarkOf(Map.of("type", "message", "aiGenerated", false)));
    }
}
