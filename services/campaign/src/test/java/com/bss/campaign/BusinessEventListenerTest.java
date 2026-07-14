package com.bss.campaign;

import com.bss.campaign.listen.BusinessEventListener;
import com.bss.campaign.service.CampaignService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Envelope in, clean (type, state, party) out — acting as the producing tenant. */
class BusinessEventListenerTest {

    private final CampaignService service = Mockito.mock(CampaignService.class);
    private final BusinessEventListener listener =
            new BusinessEventListener(service, new ObjectMapper(),
            Mockito.mock(com.bss.campaign.service.JourneyService.class));

    @Test
    void extractsTenantStateAndCustomerFromEnvelope() {
        listener.onEvent("""
                {"eventId": "e1", "eventType": "ProductOrderCreateEvent", "tenantId": "nova",
                 "event": {"productOrder": {"id": "po-1", "state": "acknowledged",
                   "relatedParty": [{"id": "party-9", "role": "customer"}]}}}
                """);
        verify(service).onEvent("ProductOrderCreateEvent", "acknowledged", "party-9");
    }

    @Test
    void eventsWithoutACustomerAreIgnored() {
        listener.onEvent("""
                {"eventId": "e2", "eventType": "ResourcePoolChangeEvent", "tenantId": "genalpha",
                 "event": {"resourcePool": {"id": "pool-1"}}}
                """);
        listener.onEvent("not json at all");
        verify(service, never()).onEvent(any(), any(), any());
    }
}
