package com.bss.som.service;

import com.bss.som.api.ApiConstants;
import com.bss.som.client.OrderingClient;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.exception.NotFoundException;
import com.bss.som.entity.ResourcePool;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final com.bss.som.client.PortingClient porting;
    private final ResourcePoolRepository pools;
    private final ResourceAssignmentRepository assignments;
    private final com.bss.som.repository.SimCardRepository sims;
    private final com.bss.som.client.CatalogClient catalog;
    private final com.bss.som.crypto.PukVault pukVault;
    private final com.bss.som.client.PartnerEntitlementClient partners;
    private final OrderingClient ordering;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.som.client.OcsProvisioningClient ocs;
    private final com.bss.som.security.TenantRegistry tenants;

    public OrchestrationService(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services, com.bss.som.client.PortingClient porting,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            com.bss.som.repository.SimCardRepository sims,
            com.bss.som.client.CatalogClient catalog,
            com.bss.som.crypto.PukVault pukVault,
            com.bss.som.client.PartnerEntitlementClient partners,
            OrderingClient ordering, DomainEventPublisher events, TenantScope tenantScope,
            com.bss.som.client.OcsProvisioningClient ocs,
            com.bss.som.security.TenantRegistry tenants) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.porting = porting;
        this.pools = pools;
        this.assignments = assignments;
        this.sims = sims;
        this.catalog = catalog;
        this.ocs = ocs;
        this.tenants = tenants;
        this.pukVault = pukVault;
        this.partners = partners;
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
        String state = String.valueOf(productOrder.get("state"));
        // acknowledged = fresh digital order; completed = physical fulfilment
        // just finished, so its digital services provision NOW. Either way,
        // never twice (at-least-once delivery upstream).
        boolean fulfilled = "completed".equals(state);
        if ((!"acknowledged".equals(state) && !fulfilled)
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
        // Plan changes (action=modify) never create a service or draw a
        // number — the existing service keeps running; only its plan name
        // follows the new offering. Idempotent: renaming twice is a no-op.
        List<Map<String, Object>> modifies = items.stream()
                .filter(i -> "modify".equalsIgnoreCase(String.valueOf(i.get("action")))).toList();
        renameModifiedServices(tenant, modifies, owner);
        items.removeAll(modifies);
        if (items.isEmpty()) {
            return;
        }
        // Fulfilment-aware: anything that ships or installs (items carrying a
        // place) completes on human fulfilment, not by the mock activator —
        // digital services activate instantly.
        boolean needsFulfilment = items.stream().anyMatch(item ->
                item.get("product") instanceof Map<?, ?> product && product.get("place") != null);
        if (needsFulfilment && !fulfilled) {
            log.info("product order {} needs physical fulfilment; SOM waits for it before activating",
                    productOrderId);
            return;
        }
        for (Map<String, Object> item : items) {
            Map<String, Object> offering = (Map<String, Object>) item.get("productOffering");
            String name = offering != null && offering.get("name") != null
                    ? String.valueOf(offering.get("name")) : "service";
            // The catalog category decides FULFILMENT, not just placement:
            // insurance is billing-only (no service at all), partner services
            // activate with the partner, security toggles a feature. Anything
            // else — or an unreachable catalog — is a network line, as always.
            String category = catalog.categoryOf(offering == null || offering.get("id") == null
                    ? null : String.valueOf(offering.get("id"))).orElse("");
            if ("Insurance".equals(category) || "Top-ups".equals(category)) {
                // insurance covers, top-ups boost an allowance — neither is a
                // service; they bill, and that's the whole story
                log.info("'{}' is billing-only ({}) — no service to provision", name, category);
                continue;
            }
            boolean partnerService = "Partner services".equals(category);
            boolean securityFeature = "Security".equals(category);
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

            // Slice services ride a delivery path (assurance re-homes them on
            // failure); AI services draw a GPU from the edge pool; everything
            // else draws an MSISDN as before.
            boolean isSlice = name != null && name.contains("Slice");
            boolean isEdgeAi = name != null && name.contains("Edge AI");
            if (isSlice) {
                instance.setDeliveryPath("fibre-route-stadium-north");
            }
            services.save(instance);

            String poolType = partnerService || securityFeature ? null
                    : isEdgeAi ? "edge-gpu" : isSlice ? null : ResourcePool.MSISDN;
            if (partnerService) {
                // the partner's platform owns the account; we hold the code
                ResourceAssignment entitlement = new ResourceAssignment();
                entitlement.setId(UUID.randomUUID().toString());
                entitlement.setTenantId(tenant);
                entitlement.setPoolId("partner");
                entitlement.setValue(partners.activate(name, owner));
                entitlement.setServiceId(serviceId);
                entitlement.setOwnerPartyId(owner);
                entitlement.setAssignedAt(OffsetDateTime.now());
                assignments.save(entitlement);
            }
            // Keep-your-number: if the customer ported a number in, activate on
            // it and skip the pool draw entirely.
            String portedNumber = poolType != null && ResourcePool.MSISDN.equals(poolType)
                    ? porting.portedNumberFor(owner) : null;
            boolean numbered = false;
            if (portedNumber != null) {
                ResourceAssignment assignment = new ResourceAssignment();
                assignment.setId(UUID.randomUUID().toString());
                assignment.setTenantId(tenant);
                assignment.setPoolId("ported");
                assignment.setValue(portedNumber);
                assignment.setServiceId(serviceId);
                assignment.setOwnerPartyId(owner);
                assignment.setAssignedAt(OffsetDateTime.now());
                assignments.save(assignment);
                numbered = true;
                poolType = null; // do not also draw from the pool
            }
            if (poolType != null && ResourcePool.MSISDN.equals(poolType)) {
                numbered = true;
            }
            if (poolType != null)
            pools.findFirstByTenantIdAndResourceType(tenant, poolType).ifPresent(pool -> {
                ResourceAssignment assignment = new ResourceAssignment();
                assignment.setId(UUID.randomUUID().toString());
                assignment.setTenantId(tenant);
                assignment.setPoolId(pool.getId());
                assignment.setValue(pool.getPrefix() + String.format("%06d", pool.getNextValue()));
                assignment.setServiceId(serviceId);
                assignment.setOwnerPartyId(instance.getOwnerPartyId());
                assignment.setAssignedAt(OffsetDateTime.now());
                assignments.save(assignment);
                pool.setNextValue(pool.getNextValue() + 1);
                pool.setLastUpdate(OffsetDateTime.now());
                pools.save(pool);
            });
            // Every numbered line rides a SIM: minted operator-side with its
            // PUK. The PIN lives on the card — set via the SIM-platform seam.
            if (numbered) {
                mintSim(tenant, serviceId);
                // charging lifecycle: the catalog references the OCS rate
                // plan (chargingSpecId); the subscriber and its counters are
                // provisioned THERE — the OCS stays the charging master
                String chargingSpec = catalog.chargingSpecOf(so.getOfferingId()).orElse(null);
                if (chargingSpec != null) {
                    ocs.provision(tenant, owner, serviceId, chargingSpec);
                }
            }

            so.setState(ServiceOrder.COMPLETED);
            so.setCompletedAt(OffsetDateTime.now());
            so.setLastUpdate(OffsetDateTime.now());
            serviceOrders.save(so);
            events.publish("ServiceOrderStateChangeEvent", "serviceOrder", Map.of(
                    "id", id, "state", so.getState(), "productOrderId", productOrderId));
        }
        // Everything active: a digital order completes itself; a fulfilled
        // order is already completed — activation was the missing half.
        if (!fulfilled) {
            ordering.complete(productOrderId);
        }
        log.info("product order {} {} by SOM ({} service orders)", productOrderId,
                fulfilled ? "activated post-fulfilment" : "completed", items.size());
    }

    /** ITU E.118-shaped ICCID (89 = telecom, 46 = country) + an 8-digit PUK. */
    public com.bss.som.entity.SimCard mintSim(String tenant, String serviceId) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder iccid = new StringBuilder("8946");
        for (int i = 0; i < 15; i++) {
            iccid.append(random.nextInt(10));
        }
        com.bss.som.entity.SimCard sim = new com.bss.som.entity.SimCard();
        sim.setIccid(iccid.toString());
        sim.setTenantId(tenant);
        sim.setServiceId(serviceId);
        sim.setPuk(pukVault.encrypt(
                String.format("%08d", random.nextInt(100_000_000)), sim.getIccid()));
        sim.setCreatedAt(OffsetDateTime.now());
        sim.setLastUpdate(OffsetDateTime.now());
        return sims.save(sim);
    }

    /**
     * A modify item carries the service it realizes (product.realizingService)
     * so the swap lands on the right line even when a customer has several.
     * Owner is checked against the order's party — a mismatching id is skipped,
     * never renamed.
     */
    private void renameModifiedServices(String tenant, List<Map<String, Object>> modifies, String owner) {
        for (Map<String, Object> item : modifies) {
            String newName = item.get("productOffering") instanceof Map<?, ?> o && o.get("name") != null
                    ? String.valueOf(o.get("name")) : null;
            String serviceId = null;
            if (item.get("product") instanceof Map<?, ?> p && p.get("realizingService") instanceof List<?> rs
                    && !rs.isEmpty() && rs.get(0) instanceof Map<?, ?> ref && ref.get("id") != null) {
                serviceId = String.valueOf(ref.get("id"));
            }
            if (newName == null || serviceId == null) {
                continue;
            }
            final String rename = newName;
            services.findByIdAndTenantId(serviceId, tenant).ifPresent(instance -> {
                if (owner != null && !owner.equals(instance.getOwnerPartyId())) {
                    log.warn("modify order names service {} owned by another party — rename skipped",
                            instance.getId());
                    return;
                }
                if (!rename.equals(instance.getName())) {
                    instance.setName(rename);
                    instance.setLastUpdate(OffsetDateTime.now());
                    services.save(instance);
                    events.publish("ServiceAttributeValueChangeEvent", "service", Map.of(
                            "id", instance.getId(), "name", rename, "state", instance.getState()));
                    log.info("plan change: service {} renamed to '{}'", instance.getId(), rename);
                    // the OCS follows the plan: swap the rate plan (rollover
                    // carried by the OCS's own policy)
                    String modifyOfferingId = item.get("productOffering") instanceof Map<?, ?> off
                            && off.get("id") != null ? String.valueOf(off.get("id")) : null;
                    catalog.chargingSpecOf(modifyOfferingId).ifPresent(chargingSpec ->
                            ocs.changeRatePlan(tenant, instance.getId(), chargingSpec));
                }
            });
        }
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

    /**
     * TEMPORARY SUSPENSION (vacation hold, mislaid phone): the line pauses,
     * the number and SIM stay the customer's, charging pauses at the OCS
     * (fail-open), and the hold lifts ITSELF at resume_at — a pause nobody
     * remembers to lift becomes a churn letter.
     */
    @Transactional
    public Map<String, Object> suspend(ServiceInstance instance, String reason,
            java.time.OffsetDateTime resumeAt) {
        if (!ServiceInstance.ACTIVE.equals(instance.getState())) {
            throw new com.bss.som.exception.BadRequestException(
                    "only an active service can be paused (state: " + instance.getState() + ")");
        }
        instance.setState(ServiceInstance.SUSPENDED);
        instance.setSuspendReason(reason);
        instance.setResumeAt(resumeAt);
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        ocs.suspend(instance.getTenantId(), instance.getId());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", instance.getId());
        event.put("name", instance.getName());
        event.put("state", instance.getState());
        event.put("reason", reason);
        if (resumeAt != null) event.put("resumeAt", resumeAt.toString());
        if (instance.getOwnerPartyId() != null) {
            event.put("relatedParty", List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer")));
        }
        events.publish("ServiceSuspendedEvent", "service", event);
        log.info("suspended service {} ({}) until {}", instance.getName(), reason, resumeAt);
        return event;
    }

    @Transactional
    public Map<String, Object> resume(ServiceInstance instance, String how) {
        if (!ServiceInstance.SUSPENDED.equals(instance.getState())) {
            throw new com.bss.som.exception.BadRequestException(
                    "only a suspended service can resume (state: " + instance.getState() + ")");
        }
        instance.setState(ServiceInstance.ACTIVE);
        instance.setSuspendReason(null);
        instance.setResumeAt(null);
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        ocs.resume(instance.getTenantId(), instance.getId());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", instance.getId());
        event.put("name", instance.getName());
        event.put("state", instance.getState());
        event.put("resumedBy", how);
        if (instance.getOwnerPartyId() != null) {
            event.put("relatedParty", List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer")));
        }
        events.publish("ServiceResumedEvent", "service", event);
        log.info("resumed service {} ({})", instance.getName(), how);
        return event;
    }

    /** The hold lifts itself: due suspensions resume, tenant by tenant
     * (acting as each so the row-level policies admit the reads). */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${bss.som.resume-tick-ms:5000}")
    public void resumeDueTick() {
        for (com.bss.som.security.TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (com.bss.som.security.TenantContext ignored =
                    com.bss.som.security.TenantContext.actAs(tenant.getId())) {
                for (ServiceInstance due : services.findTop100ByTenantIdAndStateAndResumeAtBefore(
                        tenant.getId(), ServiceInstance.SUSPENDED, OffsetDateTime.now())) {
                    try {
                        resume(due, "schedule");
                    } catch (Exception e) {
                        log.warn("auto-resume failed for {}: {}", due.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Cease a service: deactivate it, release its number back to the pool, and
     * announce the termination. Triggered by a port-out cutover (the customer
     * takes their number elsewhere) or a direct cease. Releasing the number
     * means a ported-out MSISDN is not re-issued from our pool — it's gone.
     */
    @Transactional
    public java.util.List<Map<String, Object>> terminateForParty(String party, String reason) {
        String tenant = com.bss.som.security.TenantContext.current();
        if (tenant == null) {
            tenant = tenantScope.currentTenantId();
        }
        java.util.List<Map<String, Object>> terminated = new ArrayList<>();
        for (ServiceInstance instance : services.findByTenantIdAndOwnerPartyId(tenant, party)) {
            if (ServiceInstance.TERMINATED.equals(instance.getState())) {
                continue;
            }
            terminated.add(terminate(instance, reason));
        }
        return terminated;
    }

    @Transactional
    public Map<String, Object> terminateService(String serviceId, String reason) {
        ServiceInstance instance = services.findByIdAndTenantId(serviceId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Service", serviceId));
        return terminate(instance, reason);
    }

    private Map<String, Object> terminate(ServiceInstance instance, String reason) {
        instance.setState(ServiceInstance.TERMINATED);
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        // Release the assigned number (a ported-out number leaves for good).
        String releasedNumber = null;
        for (ResourceAssignment a : assignments.findByTenantIdAndServiceId(
                instance.getTenantId(), instance.getId())) {
            releasedNumber = a.getValue();
            assignments.delete(a);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", instance.getId());
        event.put("name", instance.getName());
        event.put("state", instance.getState());
        event.put("reason", reason);
        if (releasedNumber != null) event.put("releasedNumber", releasedNumber);
        if (instance.getOwnerPartyId() != null) {
            event.put("relatedParty", List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer")));
        }
        events.publish("ServiceTerminatedEvent", "service", event);
        log.info("terminated service {} for {} ({})", instance.getName(), instance.getOwnerPartyId(), reason);
        return event;
    }

}
