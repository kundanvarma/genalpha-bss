package com.bss.som.service;

import com.bss.som.api.ApiConstants;
import com.bss.som.client.OrderingClient;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The thin SOM: a new product order decomposes into one service order per
 * item, each "activates" (TMF640 stands in for the network here — a real
 * deployment swaps the mock for activation adapters), a TMF638 service
 * record is born, and when everything is active the SOM calls the BSS back
 * to complete the product order. What used to be a staff click is now the
 * production layer doing its job.
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final ServiceOrderRepository serviceOrders;
    private final ServiceInstanceRepository services;
    private final OrderingClient ordering;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public OrchestrationService(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services,
            OrderingClient ordering, DomainEventPublisher events, TenantScope tenantScope) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.ordering = ordering;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    /**
     * Consumes a ProductOrderCreateEvent payload. Idempotent per product
     * order (at-least-once delivery upstream).
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void orchestrate(Map<String, Object> productOrder) {
        String tenant = tenantScope.currentTenantId();
        String productOrderId = String.valueOf(productOrder.get("id"));
        if (!"acknowledged".equals(productOrder.get("state"))
                || serviceOrders.existsByTenantIdAndProductOrderId(tenant, productOrderId)) {
            return;
        }
        String owner = null;
        if (productOrder.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> ref) {
            owner = String.valueOf(ref.get("id"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        collectItems((List<Map<String, Object>>) productOrder.get("productOrderItem"), items);
        if (items.isEmpty()) {
            return;
        }
        // Fulfilment-aware: anything that ships or installs (items carrying a
        // place) completes on human fulfilment, not by the mock activator —
        // digital services activate instantly.
        boolean needsFulfilment = items.stream().anyMatch(item ->
                item.get("product") instanceof Map<?, ?> product && product.get("place") != null);
        if (needsFulfilment) {
            log.info("product order {} needs physical fulfilment; SOM leaves completion to fulfilment",
                    productOrderId);
            return;
        }
        for (Map<String, Object> item : items) {
            Map<String, Object> offering = (Map<String, Object>) item.get("productOffering");
            String name = offering != null && offering.get("name") != null
                    ? String.valueOf(offering.get("name")) : "service";
            ServiceOrder so = new ServiceOrder();
            String id = UUID.randomUUID().toString();
            so.setId(id);
            so.setTenantId(tenant);
            so.setHref(ApiConstants.ORDER_BASE + "/serviceOrder/" + id);
            so.setState(ServiceOrder.IN_PROGRESS);
            so.setProductOrderId(productOrderId);
            so.setOwnerPartyId(owner);
            so.setItemName(name);
            so.setOfferingId(offering == null || offering.get("id") == null ? null
                    : String.valueOf(offering.get("id")));
            so.setCreatedAt(OffsetDateTime.now());
            so.setLastUpdate(OffsetDateTime.now());
            serviceOrders.save(so);

            // TMF640 stands in: instant mock activation. A real adapter would
            // call the network and complete asynchronously.
            ServiceInstance instance = new ServiceInstance();
            String serviceId = UUID.randomUUID().toString();
            instance.setId(serviceId);
            instance.setTenantId(tenant);
            instance.setHref(ApiConstants.INVENTORY_BASE + "/service/" + serviceId);
            instance.setName(name);
            instance.setState(ServiceInstance.ACTIVE);
            instance.setServiceOrderId(id);
            instance.setOwnerPartyId(owner);
            instance.setCreatedAt(OffsetDateTime.now());
            instance.setLastUpdate(OffsetDateTime.now());
            services.save(instance);

            so.setState(ServiceOrder.COMPLETED);
            so.setCompletedAt(OffsetDateTime.now());
            so.setLastUpdate(OffsetDateTime.now());
            serviceOrders.save(so);
            events.publish("ServiceOrderStateChangeEvent", "serviceOrder", Map.of(
                    "id", id, "state", so.getState(), "productOrderId", productOrderId));
        }
        // Everything active: the BSS order completes itself.
        ordering.complete(productOrderId);
        log.info("product order {} completed by SOM ({} service orders)", productOrderId, items.size());
    }

    @SuppressWarnings("unchecked")
    private void collectItems(List<Map<String, Object>> items, List<Map<String, Object>> into) {
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            if (item.get("productOffering") instanceof Map<?, ?> ref && ref.get("id") != null) {
                into.add(item);
            }
            if (item.get("productOrderItem") instanceof List<?> children) {
                collectItems((List<Map<String, Object>>) children, into);
            }
        }
    }
}
