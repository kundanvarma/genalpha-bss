package com.bss.som;

import com.bss.som.client.CatalogClient;
import com.bss.som.client.OrderingClient;
import com.bss.som.client.PartnerEntitlementClient;
import com.bss.som.client.PartnerEntitlementClient.Activation;
import com.bss.som.client.TenantPartnerRouter;
import com.bss.som.entity.PartnerActivation;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.repository.PartnerActivationRepository;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.service.OrchestrationService;
import com.bss.som.service.PartnerActivations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Partner activations, honest about time (#355): a refusal fails the item with
 * the partner's reason; a pending one waits and the sweep confirms it; a
 * timeout is "unknown" and the sweep ASKS before it sends again, under the
 * same key; an ending the partner could not hear still stands and is retried.
 */
@SpringBootTest(properties = "bss.partner.sweep-ms=3600000")   // the test drives the sweep by hand
@ActiveProfiles("test")
class PartnerActivationTest {

    private static final String TENANT = "genalpha";

    @Autowired
    private OrchestrationService orchestration;

    @Autowired
    private PartnerActivations activations;

    @Autowired
    private PartnerActivationRepository rows;

    @Autowired
    private ServiceInstanceRepository services;

    @Autowired
    private ResourceAssignmentRepository assignments;

    @Autowired
    private com.bss.som.client.MockPartnerEntitlementClient mockPlatform;

    @Autowired
    private com.bss.som.client.HttpPartnerEntitlementClient httpPlatform;

    @Autowired
    private com.bss.som.client.PartnerSettings settings;

    @MockitoBean
    private OrderingClient ordering;

    @MockitoBean
    private CatalogClient catalog;

    @MockitoBean
    private PartnerEntitlementClient partners;

    private final String owner = "cust-" + UUID.randomUUID();

    @BeforeEach
    void aPartnerOffering() {
        given(catalog.categoryOf("po-stream")).willReturn(Optional.of("Partner services"));
        given(partners.vendor(anyString())).willReturn("test-partner");
    }

    private String order() {
        String orderId = UUID.randomUUID().toString();
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            orchestration.orchestrate(Map.of("id", orderId, "state", "acknowledged",
                    "relatedParty", List.of(Map.of("id", owner, "role", "customer")),
                    "productOrderItem", List.of(Map.of("id", "1", "action", "add",
                            "productOffering", Map.of("id", "po-stream", "name", "StreamCo Standard")))));
        }
        return orderId;
    }

    private ServiceInstance theService() {
        return services.findByTenantIdAndOwnerPartyId(TENANT, owner).get(0);
    }

    @Test
    void aRefusal_failsTheItem_withThePartnersReason_andNoServiceComesUp() {
        given(partners.activate(eq(TENANT), anyString(), eq("StreamCo Standard"), eq(owner)))
                .willReturn(Activation.refused("not available in your region"));
        String orderId = order();
        verify(ordering).updateItemState(orderId, "1", "failed");
        assertThat(theService().getState()).isEqualTo(ServiceInstance.INACTIVE);
        PartnerActivation row = rows.findByTenantIdAndServiceId(TENANT, theService().getId()).orElseThrow();
        assertThat(row.getState()).isEqualTo(PartnerActivation.REFUSED);
        assertThat(row.getReason()).isEqualTo("not available in your region");
    }

    @Test
    void aPendingActivation_waits_thenTheSweepConfirmsIt() {
        given(partners.activate(eq(TENANT), anyString(), anyString(), anyString())).willReturn(Activation.pending());
        String orderId = order();
        verify(ordering).updateItemState(eq(orderId), eq("1"), eq("inProgress"), any());
        String serviceId = theService().getId();
        assertThat(theService().getState()).isEqualTo(ServiceInstance.INACTIVE);

        given(partners.status(TENANT, serviceId)).willReturn(Activation.active("SS-LATE-0001"));
        activations.sweep();

        verify(ordering).updateItemState(eq(orderId), eq("1"), eq("completed"), any());
        assertThat(theService().getState()).isEqualTo(ServiceInstance.ACTIVE);
        assertThat(assignments.findByTenantIdAndServiceId(TENANT, serviceId))
                .extracting(a -> a.getValue()).containsExactly("SS-LATE-0001");
    }

    @Test
    void aTimeout_isAskedAbout_beforeAnythingIsSentAgain_underTheSameKey() {
        given(partners.activate(eq(TENANT), anyString(), anyString(), anyString()))
                .willReturn(Activation.unknown("no answer in time"));
        String orderId = order();
        String serviceId = theService().getId();
        // the late success: the partner DID activate it — asking finds that, nothing is resent
        given(partners.status(TENANT, serviceId)).willReturn(Activation.active("SS-LATE-0002"));
        activations.sweep();

        verify(partners, times(1)).activate(eq(TENANT), eq(serviceId), anyString(), anyString());
        verify(ordering).updateItemState(eq(orderId), eq("1"), eq("completed"), any());
        assertThat(theService().getState()).isEqualTo(ServiceInstance.ACTIVE);
    }

    @Test
    void aRequestThePartnerNeverReceived_isSentAgain_underTheSameKey() {
        given(partners.activate(eq(TENANT), anyString(), anyString(), anyString()))
                .willReturn(Activation.unknown("no answer in time"), Activation.active("SS-RESENT-03"));
        order();
        String serviceId = theService().getId();
        given(partners.status(TENANT, serviceId)).willReturn(Activation.unknown("never received"));
        activations.sweep();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(partners, times(2)).activate(eq(TENANT), keys.capture(), anyString(), anyString());
        assertThat(keys.getAllValues()).containsOnly(serviceId);
        assertThat(theService().getState()).isEqualTo(ServiceInstance.ACTIVE);
    }

    @Test
    void anEndingThePartnerCouldNotHear_stillStands_andTheSweepTellsItAgain() {
        given(partners.activate(eq(TENANT), anyString(), anyString(), anyString())).willReturn(Activation.active("SS-END-0004"));
        order();
        ServiceInstance s = theService();
        willThrow(new IllegalStateException("partner could not deactivate (503)"))
                .given(partners).deactivate(TENANT, s.getId(), "SS-END-0004", owner);
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            orchestration.terminateService(s.getId(), "ended by the customer");
        }
        assertThat(theService().getState()).isEqualTo(ServiceInstance.TERMINATED);
        assertThat(rows.findByTenantIdAndServiceId(TENANT, s.getId()).orElseThrow().getState())
                .isEqualTo(PartnerActivation.DEACTIVATION_PENDING);

        org.mockito.Mockito.reset(partners);   // the partner is back
        activations.sweep();
        verify(partners).deactivate(TENANT, s.getId(), "SS-END-0004", owner);
        assertThat(rows.findByTenantIdAndServiceId(TENANT, s.getId()).orElseThrow().getState())
                .isEqualTo(PartnerActivation.DEACTIVATED);
    }

    @Test
    void twoTenants_twoPartnerPlatforms_eachNamedHonestly() {
        // the real router over the real adapters (the mock above stands in for it everywhere else)
        TenantPartnerRouter router = new TenantPartnerRouter(List.of(mockPlatform, httpPlatform), settings);
        assertThat(router.vendor("genalpha")).isEqualTo("mock-partner-platform");
        assertThat(router.vendor("tenant-a")).isEqualTo("partner-gateway http://partner.tenant-a.test");
        verify(partners, never()).deactivate(anyString(), anyString(), anyString(), anyString());
    }
}
