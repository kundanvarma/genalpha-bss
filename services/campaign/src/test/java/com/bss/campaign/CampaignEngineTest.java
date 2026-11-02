package com.bss.campaign;

import com.bss.campaign.client.CommunicationClient;
import com.bss.campaign.dto.CampaignPatch;
import com.bss.campaign.dto.CampaignRequest;
import com.bss.campaign.security.TenantContext;
import com.bss.campaign.service.CampaignService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The heart of martech: match, dedupe, template, deliver — exactly once. */
@SpringBootTest
@ActiveProfiles("test")
class CampaignEngineTest {

    @Autowired
    private CampaignService service;

    @MockitoBean
    private CommunicationClient communicationClient;

    private static CampaignRequest campaign(String name, String triggerEventType, String triggerState,
            String promotionCode, String subject, String content) {
        return new CampaignRequest(name, null, triggerEventType, triggerState, null, null,
                new CampaignRequest.Message(subject, content), null, promotionCode, null, null, null);
    }

    private String createWelcomeCampaign(String tenant, String triggerEventType) {
        try (TenantContext ignored = TenantContext.actAs(tenant)) {
            return service.create(campaign("Welcome journey", triggerEventType, null, "WELCOME10",
                    "Welcome!", "Use {code} on your next order.")).id();
        }
    }

    @Test
    void firesOncePerCustomerWithTemplatedCode() {
        String campaignId = createWelcomeCampaign("genalpha", "OnceProbeEvent");

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            service.onEvent("OnceProbeEvent", "acknowledged", "party-engine-1", java.util.List.of());
            service.onEvent("OnceProbeEvent", "acknowledged", "party-engine-1", java.util.List.of());
            service.onEvent("OnceProbeEvent", "completed", "party-engine-2", java.util.List.of());

            verify(communicationClient, times(1)).send(eq("party-engine-1"), eq("Welcome!"),
                    eq("Use WELCOME10 on your next order."), any(), anyMap(), any());
            verify(communicationClient, times(1)).send(
                    eq("party-engine-2"), anyString(), anyString(), any(), anyMap(), any());
            org.junit.jupiter.api.Assertions.assertEquals(2,
                    service.executionsOf(campaignId).size());
        }
    }

    @Test
    void pausedAndMismatchedTriggersStaySilent() {
        String campaignId = createWelcomeCampaign("genalpha", "PausedProbeEvent");

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            service.onEvent("SomeOtherEvent", null, "party-engine-3", java.util.List.of());
            service.patch(campaignId, new CampaignPatch("paused"));
            service.onEvent("PausedProbeEvent", null, "party-engine-3", java.util.List.of());
            verify(communicationClient, never()).send(eq("party-engine-3"), any(), any(), any(), anyMap(), any());
        }
    }

    @Test
    void triggerStateFiltersWhenSet() {
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            service.create(campaign("Order completed nudge", "ProductOrderStateChangeEvent", "completed", null,
                    "Enjoy!", "Your order is live."));

            service.onEvent("ProductOrderStateChangeEvent", "inProgress", "party-engine-4", java.util.List.of());
            verify(communicationClient, never()).send(eq("party-engine-4"), any(), any(), any(), anyMap(), any());

            service.onEvent("ProductOrderStateChangeEvent", "completed", "party-engine-4", java.util.List.of());
            verify(communicationClient, times(1)).send(eq("party-engine-4"), eq("Enjoy!"), anyString(), any(), anyMap(), any());
        }
    }

    /** EU AI Act Art. 50(2): copy from the AI copy assistant stays marked on the
     * saved campaign and every send hands communication the machine-readable mark. */
    @Test
    void aiWrittenCopyKeepsItsMarkAllTheWayToTheSend() {
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            com.bss.campaign.dto.CampaignView created = service.create(new CampaignRequest(
                    "AI welcome", null, "AiMarkProbeEvent", null, null, null,
                    new CampaignRequest.Message("Welcome aboard", "Glad to have you.", true,
                            "anthropic/claude-sonnet"), null, null, null, null, null));
            org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, created.message().aiGenerated());
            org.junit.jupiter.api.Assertions.assertEquals("anthropic/claude-sonnet", created.message().aiModel());
            // read back from the store, not just echoed from the request
            com.bss.campaign.dto.CampaignView stored = service.findAll().stream()
                    .filter(c -> c.id().equals(created.id())).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, stored.message().aiGenerated());
            org.junit.jupiter.api.Assertions.assertEquals("anthropic/claude-sonnet", stored.message().aiModel());

            service.onEvent("AiMarkProbeEvent", null, "party-engine-ai", java.util.List.of());
            verify(communicationClient, times(1)).send(eq("party-engine-ai"), eq("Welcome aboard"),
                    eq("Glad to have you."), any(), anyMap(),
                    eq(new CommunicationClient.AiMark("anthropic/claude-sonnet")));
        }
    }

    @Test
    void humanCopyGoesOutUnmarked() {
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            com.bss.campaign.dto.CampaignView created = service.create(campaign("Human note",
                    "HumanMarkProbeEvent", null, null, "Hello", "Written by a person."));
            org.junit.jupiter.api.Assertions.assertNull(created.message().aiGenerated());
            service.onEvent("HumanMarkProbeEvent", null, "party-engine-human", java.util.List.of());
            verify(communicationClient, times(1)).send(eq("party-engine-human"), eq("Hello"),
                    eq("Written by a person."), any(), anyMap(), isNull());
        }
    }

    @Test
    void campaignsAreTenantLocal() {
        createWelcomeCampaign("tenant-a", "TenantProbeEvent");

        try (TenantContext ignored = TenantContext.actAs("tenant-b")) {
            service.onEvent("TenantProbeEvent", null, "party-engine-5", java.util.List.of());
        }
        verify(communicationClient, never()).send(eq("party-engine-5"), any(), any(), any(), anyMap(), any());
    }
}
