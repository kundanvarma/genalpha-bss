package com.bss.campaign;

import com.bss.campaign.client.CommunicationClient;
import com.bss.campaign.security.TenantContext;
import com.bss.campaign.service.CampaignService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The heart of martech: match, dedupe, template, deliver — exactly once. */
@SpringBootTest
@ActiveProfiles("test")
class CampaignEngineTest {

    @Autowired
    private CampaignService service;

    @MockBean
    private CommunicationClient communicationClient;

    private String createWelcomeCampaign(String tenant, String triggerEventType) {
        try (TenantContext ignored = TenantContext.actAs(tenant)) {
            return String.valueOf(service.create(Map.of(
                    "name", "Welcome journey",
                    "triggerEventType", triggerEventType,
                    "promotionCode", "WELCOME10",
                    "message", Map.of("subject", "Welcome!",
                            "content", "Use {code} on your next order."))).get("id"));
        }
    }

    @Test
    void firesOncePerCustomerWithTemplatedCode() {
        String campaignId = createWelcomeCampaign("genalpha", "OnceProbeEvent");

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            service.onEvent("OnceProbeEvent", "acknowledged", "party-engine-1", java.util.List.of());
            service.onEvent("OnceProbeEvent", "acknowledged", "party-engine-1", java.util.List.of());
            service.onEvent("OnceProbeEvent", "completed", "party-engine-2", java.util.List.of());

            verify(communicationClient, times(1)).send(
                    eq("party-engine-1"), eq("Welcome!"), eq("Use WELCOME10 on your next order."));
            verify(communicationClient, times(1)).send(
                    eq("party-engine-2"), anyString(), anyString());
            org.junit.jupiter.api.Assertions.assertEquals(2,
                    service.executionsOf(campaignId).size());
        }
    }

    @Test
    void pausedAndMismatchedTriggersStaySilent() {
        String campaignId = createWelcomeCampaign("genalpha", "PausedProbeEvent");

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            service.onEvent("SomeOtherEvent", null, "party-engine-3", java.util.List.of());
            service.patch(campaignId, Map.of("status", "paused"));
            service.onEvent("PausedProbeEvent", null, "party-engine-3", java.util.List.of());
            verify(communicationClient, never()).send(eq("party-engine-3"), any(), any());
        }
    }

    @Test
    void triggerStateFiltersWhenSet() {
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            service.create(Map.of(
                    "name", "Order completed nudge",
                    "triggerEventType", "ProductOrderStateChangeEvent",
                    "triggerState", "completed",
                    "message", Map.of("subject", "Enjoy!", "content", "Your order is live.")));

            service.onEvent("ProductOrderStateChangeEvent", "inProgress", "party-engine-4", java.util.List.of());
            verify(communicationClient, never()).send(eq("party-engine-4"), any(), any());

            service.onEvent("ProductOrderStateChangeEvent", "completed", "party-engine-4", java.util.List.of());
            verify(communicationClient, times(1)).send(eq("party-engine-4"), eq("Enjoy!"), anyString());
        }
    }

    @Test
    void campaignsAreTenantLocal() {
        createWelcomeCampaign("tenant-a", "TenantProbeEvent");

        try (TenantContext ignored = TenantContext.actAs("tenant-b")) {
            service.onEvent("TenantProbeEvent", null, "party-engine-5", java.util.List.of());
        }
        verify(communicationClient, never()).send(eq("party-engine-5"), any(), any());
    }
}
