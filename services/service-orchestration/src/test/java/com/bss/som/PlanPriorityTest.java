package com.bss.som;

import com.bss.som.client.CatalogClient;
import com.bss.som.client.OrderingClient;
import com.bss.som.client.PartnerEntitlementClient;
import com.bss.som.client.SliceProvisioningClient;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.service.OrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * A line's priority follows whoever pays for it (#393, review): a plan change
 * away from a Priority plan hands the slice to the "Priority on my line" Pick
 * the household still pays for — or takes it away when nothing does — and
 * releasing that Pick ends the slice it then owns.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanPriorityTest {

    private static final String TENANT = "genalpha";

    @Autowired
    private OrchestrationService orchestration;

    @Autowired
    private ServiceInstanceRepository services;

    @MockitoBean
    private CatalogClient catalog;

    @MockitoBean
    private SliceProvisioningClient slices;

    @MockitoBean
    private OrderingClient ordering;

    @MockitoBean
    private PartnerEntitlementClient partners;

    @BeforeEach
    void catalog() {
        given(catalog.sliceIntentOf(anyString())).willReturn(Optional.empty());   // the plan changed to: no priority
        given(catalog.chargingSpecOf(anyString())).willReturn(Optional.empty());
    }

    private ServiceInstance priorityLine(String owner, String planOrder, String addonOrder) {
        ServiceInstance s = new ServiceInstance();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(TENANT);
        s.setName("GenAlpha Mobile Priority");
        s.setState(ServiceInstance.ACTIVE);
        s.setServiceOrderId(UUID.randomUUID().toString());
        s.setOwnerPartyId(owner);
        s.setSliceProfile("priority");
        s.setSliceOrderId(planOrder);
        s.setSliceAddonOrderId(addonOrder);
        s.setCreatedAt(OffsetDateTime.now());
        s.setLastUpdate(OffsetDateTime.now());
        return services.save(s);
    }

    private static Map<String, Object> downgrade(String owner, String serviceId) {
        return Map.of("id", "ord-down-" + UUID.randomUUID(), "state", "acknowledged",
                "relatedParty", List.of(Map.of("id", owner, "role", "customer")),
                "productOrderItem", List.of(Map.of("id", "1", "action", "modify",
                        "productOffering", Map.of("id", "po-mobile-10", "name", "GenAlpha Mobile 10 GB"),
                        "product", Map.of("id", "prod-plan", "realizingService", List.of(Map.of("id", serviceId))))));
    }

    private static Map<String, Object> release(String owner, String addonOrder) {
        return Map.of("id", "ord-rel-" + UUID.randomUUID(), "state", "acknowledged",
                "relatedParty", List.of(Map.of("id", owner, "role", "customer")),
                "productOrderItem", List.of(Map.of("id", "1", "action", "delete",
                        "productOffering", Map.of("id", "po-prio", "name", "Priority on my line"),
                        "product", Map.of("id", "prod-prio",
                                "productOrderItem", List.of(Map.of("productOrderId", addonOrder, "orderItemId", "1"))))));
    }

    private ServiceInstance reload(ServiceInstance s) {
        return services.findByIdAndTenantId(s.getId(), TENANT).orElseThrow();
    }

    @Test
    void downgradingAPriorityPlan_keepsPriorityWhileThePickPays_andReleasingThePickEndsIt() {
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            String owner = "cust-" + UUID.randomUUID();
            ServiceInstance line = priorityLine(owner, "ord-plan", "ord-addon");

            orchestration.orchestrate(downgrade(owner, line.getId()));
            ServiceInstance after = reload(line);
            assertThat(after.getSliceProfile()).as("the Pick still pays for priority").isEqualTo("priority");
            assertThat(after.getSliceOrderId()).isEqualTo("ord-addon");

            orchestration.orchestrate(release(owner, "ord-addon"));
            assertThat(reload(line).getSliceProfile()).as("nothing pays for priority any more").isNull();
        }
    }

    @Test
    void downgradingAPriorityPlan_withNoPick_takesItsPriorityAway() {
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            String owner = "cust-" + UUID.randomUUID();
            ServiceInstance line = priorityLine(owner, "ord-plan", null);
            orchestration.orchestrate(downgrade(owner, line.getId()));
            assertThat(reload(line).getSliceProfile()).isNull();
        }
    }

    @Test
    void releasingAPickThatOnlyStoodBy_keepsThePlansPriority() {
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            String owner = "cust-" + UUID.randomUUID();
            ServiceInstance line = priorityLine(owner, "ord-plan", "ord-addon");
            orchestration.orchestrate(release(owner, "ord-addon"));
            ServiceInstance after = reload(line);
            assertThat(after.getSliceProfile()).isEqualTo("priority");
            assertThat(after.getSliceOrderId()).isEqualTo("ord-plan");
            assertThat(after.getSliceAddonOrderId()).isNull();
        }
    }
}
