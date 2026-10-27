package com.bss.som;

import com.bss.som.client.OrderingClient;
import com.bss.som.client.PartnerEntitlementClient;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.repository.NumberQuarantineRepository;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.service.OrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Ending a product (TMF622 action=delete, #353): the orchestrator stops the
 * service the delete item names, tells the partner to deactivate a partner
 * service exactly once, and never shelves a partner code as a number.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductEndingTest {

    private static final String TENANT = "genalpha";

    @Autowired
    private OrchestrationService orchestration;

    @Autowired
    private ServiceInstanceRepository services;

    @Autowired
    private ResourceAssignmentRepository assignments;

    @Autowired
    private NumberQuarantineRepository quarantine;

    @MockitoBean
    private OrderingClient ordering;

    @MockitoBean
    private PartnerEntitlementClient partners;

    private ServiceInstance partnerService(String owner, String code) {
        ServiceInstance s = new ServiceInstance();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(TENANT);
        s.setName("StreamCo Standard");
        s.setState("active");
        s.setServiceOrderId(UUID.randomUUID().toString());
        s.setOwnerPartyId(owner);
        s.setCreatedAt(OffsetDateTime.now());
        s.setLastUpdate(OffsetDateTime.now());
        services.save(s);
        ResourceAssignment a = new ResourceAssignment();
        a.setId(UUID.randomUUID().toString());
        a.setTenantId(TENANT);
        a.setPoolId("partner");
        a.setValue(code);
        a.setServiceId(s.getId());
        a.setOwnerPartyId(owner);
        a.setAssignedAt(OffsetDateTime.now());
        assignments.save(a);
        return s;
    }

    private static Map<String, Object> deleteOrder(String orderId, String state, String owner, String serviceId) {
        return Map.of("id", orderId, "state", state,
                "relatedParty", List.of(Map.of("id", owner, "role", "customer")),
                "productOrderItem", List.of(Map.of("id", "1", "action", "delete",
                        "productOffering", Map.of("id", "po-stream", "name", "StreamCo Standard"),
                        "product", Map.of("id", "prod-1", "realizingService", List.of(Map.of("id", serviceId))))));
    }

    @Test
    void endingAPartnerService_stopsItAndTellsThePartnerOnce() {
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            ServiceInstance s = partnerService("cust-end", "SS-ABCD-EFGH");
            String orderId = "po-end-" + UUID.randomUUID();
            orchestration.orchestrate(deleteOrder(orderId, "acknowledged", "cust-end", s.getId()));
            // a delete-only order completes at once: the completed event arrives too
            orchestration.orchestrate(deleteOrder(orderId, "completed", "cust-end", s.getId()));

            assertThat(services.findByIdAndTenantId(s.getId(), TENANT).orElseThrow().getState())
                    .isEqualTo(ServiceInstance.TERMINATED);
            verify(partners, times(1)).deactivate(TENANT, s.getId(), "SS-ABCD-EFGH", "cust-end");
            // the code left with the service; it never landed on the number shelf
            assertThat(assignments.findByTenantIdAndServiceId(TENANT, s.getId())).isEmpty();
            assertThat(quarantine.findAll()).noneMatch(q -> "SS-ABCD-EFGH".equals(q.getNumber()));
        }
    }

    @Test
    void anOrderNamingSomeoneElsesService_endsNothing() {
        try (TenantContext ignored = TenantContext.actAs(TENANT)) {
            ServiceInstance s = partnerService("cust-owner", "SS-WXYZ-2345");
            orchestration.orchestrate(deleteOrder("po-end-" + UUID.randomUUID(), "acknowledged",
                    "cust-intruder", s.getId()));

            assertThat(services.findByIdAndTenantId(s.getId(), TENANT).orElseThrow().getState())
                    .isEqualTo("active");
            verify(partners, never()).deactivate(anyString(), anyString(), anyString(), anyString());
        }
    }
}
