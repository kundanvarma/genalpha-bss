package com.bss.assurance;

import com.bss.assurance.client.SelfHealClients;
import com.bss.assurance.events.DomainEventPublisher;
import com.bss.assurance.service.SelfHealService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The autonomy unit: a fibre path fails, every slice on it moves to the edge. */
class SelfHealServiceTest {

    private final SelfHealClients clients = Mockito.mock(SelfHealClients.class);
    private final DomainEventPublisher events = Mockito.mock(DomainEventPublisher.class);
    private final SelfHealService selfHeal = new SelfHealService(clients, events);

    @Test
    void reHomesEverySliceOnTheFailedFibrePathAndOpensATicket() {
        when(clients.servicesOnPath("fibre-route-stadium-north")).thenReturn(List.of(
                Map.of("id", "svc-1", "name", "Stadium 5G Slice",
                        "relatedParty", List.of(Map.of("id", "stadium-org", "role", "customer")))));

        int healed = selfHeal.attemptHeal("fibre-route-stadium-north");

        assertThat(healed).isEqualTo(1);
        verify(clients).migrate("svc-1", "edge:gpu-site-stadium-north");
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(clients).openTicket(name.capture(), any(), eq("stadium-org"));
        assertThat(name.getValue()).contains("re-homed to edge");
        verify(events).publish(eq("ServiceHealedEvent"), any(), any());
    }

    @Test
    void nonFibreObjectsAreNotOurJobToHeal() {
        int healed = selfHeal.attemptHeal("olt-district-9");
        assertThat(healed).isZero();
        verify(clients, never()).migrate(any(), any());
    }
}
