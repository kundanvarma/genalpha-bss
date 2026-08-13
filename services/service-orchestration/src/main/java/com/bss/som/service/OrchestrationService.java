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
    private final com.bss.som.repository.StarterKitRepository starterKits;
    private final com.bss.som.repository.DealerAgreementRepository dealerAgreements;
    private final com.bss.som.repository.CommissionEntryRepository commissionEntries;
    private final long commissionWindowMs;
    private final com.bss.som.client.PartnerEntitlementClient partners;
    private final OrderingClient ordering;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.som.client.OcsProvisioningClient ocs;
    private final com.bss.som.security.TenantRegistry tenants;
    private final com.bss.som.repository.NumberQuarantineRepository quarantine;
    private final com.bss.som.tick.TickGuard tickGuard;
    private final com.bss.som.repository.WholesaleAccessOrderRepository wholesaleOrders;
    private final com.bss.som.client.WholesaleQualificationClient wholesaleQualification;
    private final com.bss.som.client.WholesaleAccessClient wholesaleAccess;
    private final com.bss.som.client.WholesaleRateCardClient wholesaleRateCard;

    public OrchestrationService(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services, com.bss.som.client.PortingClient porting,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            com.bss.som.repository.SimCardRepository sims,
            com.bss.som.client.CatalogClient catalog,
            com.bss.som.crypto.PukVault pukVault,
            com.bss.som.repository.StarterKitRepository starterKits,
            com.bss.som.repository.DealerAgreementRepository dealerAgreements,
            com.bss.som.repository.CommissionEntryRepository commissionEntries,
            @org.springframework.beans.factory.annotation.Value(
                    "${bss.som.commission-window-ms:1209600000}") long commissionWindowMs,
            com.bss.som.client.PartnerEntitlementClient partners,
            OrderingClient ordering, DomainEventPublisher events, TenantScope tenantScope,
            com.bss.som.client.OcsProvisioningClient ocs,
            com.bss.som.security.TenantRegistry tenants,
            com.bss.som.repository.NumberQuarantineRepository quarantine,
            com.bss.som.tick.TickGuard tickGuard,
            com.bss.som.repository.WholesaleAccessOrderRepository wholesaleOrders,
            com.bss.som.client.WholesaleQualificationClient wholesaleQualification,
            com.bss.som.client.WholesaleAccessClient wholesaleAccess,
            com.bss.som.client.WholesaleRateCardClient wholesaleRateCard) {
        this.wholesaleOrders = wholesaleOrders;
        this.wholesaleQualification = wholesaleQualification;
        this.wholesaleAccess = wholesaleAccess;
        this.wholesaleRateCard = wholesaleRateCard;
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.porting = porting;
        this.pools = pools;
        this.assignments = assignments;
        this.sims = sims;
        this.catalog = catalog;
        this.ocs = ocs;
        this.tenants = tenants;
        this.quarantine = quarantine;
        this.pukVault = pukVault;
        this.starterKits = starterKits;
        this.dealerAgreements = dealerAgreements;
        this.commissionEntries = commissionEntries;
        this.commissionWindowMs = commissionWindowMs;
        this.partners = partners;
        this.ordering = ordering;
        this.events = events;
        this.tenantScope = tenantScope;
        this.tickGuard = tickGuard;
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
        boolean alreadyOrchestrated = serviceOrders.existsByTenantIdAndProductOrderId(tenant, productOrderId);
        if (fulfilled && alreadyOrchestrated) {
            // C4 — fulfilment finished: complete the physical items' service orders
            // that were HELD inProgress at order time, so the process layer's
            // provisioned/fulfilled milestones fire on REAL fulfilment (a delivered
            // parcel / a done install), not on optimistic activation.
            completeDeferredServiceOrders(tenant, productOrderId);
            return;
        }
        if ((!"acknowledged".equals(state) && !fulfilled) || alreadyOrchestrated) {
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
        // C1 — INDEPENDENT per-component fulfillment. No order-level gate: a
        // digital service (mobile eSIM, TV, add-on) activates NOW and its item
        // is reported completed, while an item that ships or installs (carries a
        // place) is reported inProgress and finishes on its own track — the
        // parcel's delivery (C2) or the install (C4). The parent order rolls up
        // to partiallyCompleted and only reaches completed when every item lands.
        // Which components are being SHIPPED or INSTALLED (they carry a place) —
        // a dependent (TV) only waits when the base it rides is actually one of
        // these; when the base activates instantly, so may the dependent.
        java.util.Set<String> placedItemIds = new java.util.HashSet<>();
        for (Map<String, Object> it : items) {
            if (it.get("id") != null && it.get("product") instanceof Map<?, ?> pr
                    && pr.get("place") != null) {
                placedItemIds.add(String.valueOf(it.get("id")));
            }
        }
        boolean anyUnreported = false;
        for (Map<String, Object> item : items) {
            String itemId = item.get("id") != null ? String.valueOf(item.get("id")) : null;
            boolean deferred = item.get("product") instanceof Map<?, ?> product && product.get("place") != null;
            // Choose-your-number: the shopper's picked MSISDN rides the item as
            // a product characteristic — honored below IF still free.
            String wishNumber = null;
            if (item.get("product") instanceof Map<?, ?> prod
                    && prod.get("productCharacteristic") instanceof List<?> chars) {
                for (Object c : chars) {
                    if (c instanceof Map<?, ?> ch && "msisdn".equals(String.valueOf(ch.get("name")))
                            && ch.get("value") != null) {
                        wishNumber = String.valueOf(ch.get("value"));
                    }
                }
            }
            final String wish = wishNumber;
            Map<String, Object> offering = (Map<String, Object>) item.get("productOffering");
            // the SERVICE must carry the offering's real name (the product
            // record does, and downstream correlates the two by it) — an
            // order item that names only the id gets the name from the catalog
            String name = offering != null && offering.get("name") != null
                    ? String.valueOf(offering.get("name"))
                    : offering != null && offering.get("id") != null
                            ? catalog.nameOf(String.valueOf(offering.get("id"))).orElse("service")
                            : "service";
            // The catalog category decides FULFILMENT, not just placement:
            // insurance is billing-only (no service at all), partner services
            // activate with the partner, security toggles a feature. Anything
            // else — or an unreachable catalog — is a network line, as always.
            String category = catalog.categoryOf(offering == null || offering.get("id") == null
                    ? null : String.valueOf(offering.get("id"))).orElse("");
            if ("Insurance".equals(category) || "Top-ups".equals(category)) {
                // insurance covers, top-ups boost an allowance — neither is a
                // service; they bill, and that's the whole story. Nothing to
                // fulfil, so the item is done.
                log.info("'{}' is billing-only ({}) — no service to provision", name, category);
                if (itemId != null) {
                    ordering.updateItemState(productOrderId, itemId, "completed");
                }
                continue;
            }
            boolean partnerService = "Partner services".equals(category);
            boolean securityFeature = "Security".equals(category);
            // The decomposed component's family decides its fulfilment: a mobile
            // plan is a network line (number + SIM); broadband installs and never
            // draws a number; TV is a digital entitlement; a handset ships and is
            // not a line at all. Unknown/other keeps the historical line behaviour.
            String componentType = componentType(category);
            boolean internet = "internet".equals(componentType);
            boolean tv = "tv".equals(componentType);
            boolean device = "device".equals(componentType);
            // Fulfilment dependency (TMF622 "reliesOn"): a component that rides
            // another — TV on the broadband connection — must NOT activate while
            // its base is still being set up. Hold it inProgress ONLY while that
            // base is actually being installed/shipped (it carries a place); when
            // the base activates instantly, the dependent may too. It then comes up
            // with the deferred fan-out, so the customer never sees TV "done" while
            // the internet it needs is still pending.
            boolean reliesOnPending = false;
            if (item.get("orderItemRelationship") instanceof List<?> rels) {
                for (Object r : rels) {
                    if (r instanceof Map<?, ?> rel
                            && "reliesOn".equals(String.valueOf(rel.get("relationshipType")))
                            && placedItemIds.contains(String.valueOf(rel.get("id")))) {
                        reliesOnPending = true;
                    }
                }
            }
            if (reliesOnPending) {
                deferred = true;
            }
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

            // OPEN ACCESS: a broadband component may be delivered over a THIRD-PARTY
            // owner's fibre. If owners serve this address, place the access-seeker
            // order UPSTREAM and realize the retail line over it — instead of our own
            // install. The owner OSS activates it (mock: instantly), so the retail
            // line comes up here rather than waiting on a workOrder of ours.
            if (internet && provisionWholesaleAccess(tenant, item, serviceId, owner, productOrderId)) {
                deferred = false;
            }

            // Only a mobile line (or an unknown/other offering, historically) draws
            // an MSISDN + SIM. Broadband, TV and handsets are not phone lines.
            boolean nonLine = partnerService || securityFeature || isSlice
                    || internet || tv || device;
            String poolType = nonLine ? null : isEdgeAi ? "edge-gpu" : ResourcePool.MSISDN;
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
                // Choose-your-number: the wish wins while it is still FREE; a
                // lost race (two shoppers, one number) falls back to next-free
                // — the first pick stands, honestly.
                String value = null;
                if (wish != null && ResourcePool.MSISDN.equals(pool.getResourceType())
                        && assignments.findFirstByTenantIdAndValue(tenant, wish).isEmpty()) {
                    value = wish;
                }
                if (value == null) {
                    // next FREE — skips any value a wish already consumed from
                    // the window ahead, so the counter can never mint a dupe
                    long next = pool.getNextValue();
                    String candidate = pool.getPrefix() + String.format("%06d", next);
                    while (assignments.findFirstByTenantIdAndValue(tenant, candidate).isPresent()) {
                        next++;
                        candidate = pool.getPrefix() + String.format("%06d", next);
                    }
                    value = candidate;
                    pool.setNextValue(next + 1);
                    pool.setLastUpdate(OffsetDateTime.now());
                    pools.save(pool);
                }
                ResourceAssignment assignment = new ResourceAssignment();
                assignment.setId(UUID.randomUUID().toString());
                assignment.setTenantId(tenant);
                assignment.setPoolId(pool.getId());
                assignment.setValue(value);
                assignment.setServiceId(serviceId);
                assignment.setOwnerPartyId(instance.getOwnerPartyId());
                assignment.setAssignedAt(OffsetDateTime.now());
                assignments.save(assignment);
            });
            // Every numbered line rides a SIM: minted operator-side with its
            // PUK. The PIN lives on the card — set via the SIM-platform seam.
            if (numbered) {
                attachKitSimOrMint(tenant, serviceId, productOrderId);
                accrueCommission(tenant, productOrder, productOrderId, serviceId, name);
                // charging lifecycle: the catalog references the OCS rate
                // plan (chargingSpecId); the subscriber and its counters are
                // provisioned THERE — the OCS stays the charging master
                String chargingSpec = catalog.chargingSpecOf(so.getOfferingId()).orElse(null);
                if (chargingSpec != null) {
                    ocs.provision(tenant, owner, serviceId, chargingSpec);
                }
            }

            // C4 — a physical item's service order is HELD inProgress until its
            // parcel/install actually lands; only a digital service completes NOW.
            // The line/SIM is already ACTIVE either way (number drawn, OCS
            // provisioned, ICCID bound before dispatch) — this is the order's
            // fulfilment-tracking state, which drives the process 'provisioned'
            // milestone. Holding it keeps that milestone honest for a stranded
            // physical order instead of optimistically completing it.
            if (deferred) {
                so.setLastUpdate(OffsetDateTime.now());
                serviceOrders.save(so);
            } else {
                so.setState(ServiceOrder.COMPLETED);
                so.setCompletedAt(OffsetDateTime.now());
                so.setLastUpdate(OffsetDateTime.now());
                serviceOrders.save(so);
                events.publish("ServiceOrderStateChangeEvent", "serviceOrder", Map.of(
                        "id", id, "state", so.getState(), "productOrderId", productOrderId));
            }
            // C1: report THIS item's fulfillment state. A physical item (ships
            // or installs — it carries a place) stays inProgress until its own
            // track lands (parcel delivered / install done); a digital service
            // is done now. The parent order rolls up from these per-item states.
            if (itemId != null) {
                ordering.updateItemState(productOrderId, itemId, deferred ? "inProgress" : "completed");
            } else {
                anyUnreported = true;
            }
        }
        // Safety net for orders whose items predate per-item ids (C0): fall back
        // to the whole-order completion so nothing is left stranded.
        if (!fulfilled && anyUnreported) {
            ordering.complete(productOrderId);
        }
        log.info("product order {} orchestrated by SOM ({} service orders)", productOrderId, items.size());
    }

    /**
     * C4 — when fulfilment completes the order, finish the physical items' service
     * orders that were held IN_PROGRESS at order time, emitting the provisioning
     * event now. This makes the process layer's provisioned/fulfilled milestones
     * reflect real physical fulfilment; a stranded order (no delivery) leaves them
     * inProgress, so the stuck-sweep can honestly flag it.
     */
    private void completeDeferredServiceOrders(String tenant, String productOrderId) {
        int completed = 0;
        for (ServiceOrder so : serviceOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)) {
            if (ServiceOrder.IN_PROGRESS.equals(so.getState())) {
                so.setState(ServiceOrder.COMPLETED);
                so.setCompletedAt(OffsetDateTime.now());
                so.setLastUpdate(OffsetDateTime.now());
                serviceOrders.save(so);
                events.publish("ServiceOrderStateChangeEvent", "serviceOrder", Map.of(
                        "id", so.getId(), "state", so.getState(), "productOrderId", productOrderId));
                completed++;
            }
        }
        if (completed > 0) {
            log.info("product order {} — {} deferred service order(s) completed post-fulfilment",
                    productOrderId, completed);
        }
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

    /** A KIT order rides the SIM from the box; anything else mints fresh.
     * The kit was stamped with the product order at activation time. */
    private void attachKitSimOrMint(String tenant, String serviceId, String productOrderId) {
        com.bss.som.entity.StarterKit kit = starterKits
                .findFirstByTenantIdAndProductOrderId(tenant, productOrderId).orElse(null);
        if (kit == null) {
            mintSim(tenant, serviceId);
            return;
        }
        com.bss.som.entity.SimCard sim = new com.bss.som.entity.SimCard();
        sim.setIccid(kit.getIccid());
        sim.setTenantId(tenant);
        sim.setServiceId(serviceId);
        sim.setPuk(kit.getPukCiphertext());
        sim.setCreatedAt(OffsetDateTime.now());
        sim.setLastUpdate(OffsetDateTime.now());
        sims.save(sim);
        log.info("starter kit SIM {} attached to service {} (kit {})",
                kit.getIccid(), serviceId, kit.getActivationCode());
    }

    /**
     * A dealer-stamped activation EARNS the store its commission — but
     * PENDING first: Norway's 14-day angrerett means the money only
     * hardens after the withdrawal window, and an early leave claws it
     * back. Idempotency rides orchestrate()'s own once-per-order guard.
     */
    private void accrueCommission(String tenant, Map<String, Object> productOrder,
            String productOrderId, String serviceId, String offeringName) {
        if (!(productOrder.get("relatedParty") instanceof List<?> parties)) {
            return;
        }
        Map<String, Object> dealerRef = null;
        String customerId = null;
        for (Object p : parties) {
            if (p instanceof Map<?, ?> ref) {
                if ("dealer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                    dealerRef = (Map<String, Object>) ref;
                } else if ("customer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                    customerId = String.valueOf(ref.get("id"));
                }
            }
        }
        if (dealerRef == null) {
            return;
        }
        com.bss.som.entity.DealerAgreement agreement = dealerAgreements
                .findByTenantIdAndDealerOrgId(tenant, String.valueOf(dealerRef.get("id")))
                .orElse(null);
        if (agreement == null) {
            log.warn("order {} names dealer {} but no agreement exists — no commission",
                    productOrderId, dealerRef.get("id"));
            return;
        }
        com.bss.som.entity.CommissionEntry entry = new com.bss.som.entity.CommissionEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setTenantId(tenant);
        entry.setDealerOrgId(agreement.getDealerOrgId());
        entry.setStore(dealerRef.get("name") == null ? null : String.valueOf(dealerRef.get("name")));
        entry.setProductOrderId(productOrderId);
        entry.setServiceId(serviceId);
        entry.setCustomerPartyId(customerId);
        entry.setOfferingName(offeringName);
        entry.setDeviceNote(dealerRef.get("device") == null ? null
                : String.valueOf(dealerRef.get("device")));
        entry.setAmountValue(agreement.getCommissionValue());
        entry.setAmountUnit(agreement.getCommissionUnit());
        entry.setStatus(com.bss.som.entity.CommissionEntry.PENDING);
        entry.setAccruedAt(OffsetDateTime.now());
        entry.setHardensAt(OffsetDateTime.now().plus(
                java.time.Duration.ofMillis(commissionWindowMs)));
        entry.setLastUpdate(OffsetDateTime.now());
        commissionEntries.save(entry);
        log.info("commission accrued (pending): {} {} to {} for activation {}",
                entry.getAmountValue(), entry.getAmountUnit(), agreement.getName(), serviceId);
    }

    /** After the withdrawal window, pending money HARDENS. */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${bss.som.commission-tick-ms:3600000}")
    public void hardenCommissionTick() {
        if (!tickGuard.claim("commission-harden", java.time.Duration.ofSeconds(60))) {
            return; // another replica hardens the money — once
        }
        try {
            for (com.bss.som.security.TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (var ignored = com.bss.som.security.TenantContext.actAs(tenant.getId())) {
                    for (com.bss.som.entity.CommissionEntry entry : commissionEntries
                            .findTop100ByTenantIdAndStatusAndHardensAtBefore(tenant.getId(),
                                    com.bss.som.entity.CommissionEntry.PENDING, OffsetDateTime.now())) {
                        entry.setStatus(com.bss.som.entity.CommissionEntry.EARNED);
                        entry.setLastUpdate(OffsetDateTime.now());
                        commissionEntries.save(entry);
                        log.info("commission earned: {} {} to {} ({})", entry.getAmountValue(),
                                entry.getAmountUnit(), entry.getDealerOrgId(), entry.getServiceId());
                    }
                } catch (Exception e) {
                    log.warn("commission harden tick failed for {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("commission-harden");
        }
    }

    /** The customer left inside the window: the pending money goes back. */
    private void clawBackCommission(String tenant, String serviceId) {
        for (com.bss.som.entity.CommissionEntry entry
                : commissionEntries.findByTenantIdAndServiceId(tenant, serviceId)) {
            if (com.bss.som.entity.CommissionEntry.PENDING.equals(entry.getStatus())) {
                entry.setStatus(com.bss.som.entity.CommissionEntry.CLAWED_BACK);
                entry.setReason("service terminated inside the withdrawal window");
                entry.setLastUpdate(OffsetDateTime.now());
                commissionEntries.save(entry);
                log.info("commission clawed back: {} {} from {} — the customer left inside the window",
                        entry.getAmountValue(), entry.getAmountUnit(), entry.getDealerOrgId());
            }
        }
    }

    /**
     * A modify item carries the service it realizes (product.realizingService)
     * so the swap lands on the right line even when a customer has several.
     * Owner is checked against the order's party — a mismatching id is skipped,
     * never renamed.
     */
    @SuppressWarnings("unchecked")
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
            // the new characteristic values (speed, TV pack) an in-place upgrade
            // carries — a real network adapter would apply them to the line
            List<Map<String, Object>> newChars = item.get("product") instanceof Map<?, ?> mp
                    && ((Map<String, Object>) mp).get("productCharacteristic") instanceof List<?> mc
                    ? (List<Map<String, Object>>) mc : null;
            String modifyOfferingId = item.get("productOffering") instanceof Map<?, ?> off
                    && off.get("id") != null ? String.valueOf(off.get("id")) : null;
            services.findByIdAndTenantId(serviceId, tenant).ifPresent(instance -> {
                if (owner != null && !owner.equals(instance.getOwnerPartyId())) {
                    log.warn("modify order names service {} owned by another party — change skipped",
                            instance.getId());
                    return;
                }
                boolean renamed = !rename.equals(instance.getName());
                if (renamed) {
                    instance.setName(rename);
                    instance.setLastUpdate(OffsetDateTime.now());
                    services.save(instance);
                    log.info("plan change: service {} renamed to '{}'", instance.getId(), rename);
                    // the OCS follows the plan: swap the rate plan (rollover
                    // carried by the OCS's own policy)
                    catalog.chargingSpecOf(modifyOfferingId).ifPresent(chargingSpec ->
                            ocs.changeRatePlan(tenant, instance.getId(), chargingSpec));
                }
                // an in-place upgrade/downgrade re-provisions the line to the new
                // characteristic (broadband speed, TV screens/points) — same
                // offering, new capability. The mock emits the attribute change a
                // network adapter would apply; billing rates the conditioned price.
                if (renamed || newChars != null) {
                    Map<String, Object> event = new java.util.LinkedHashMap<>();
                    event.put("id", instance.getId());
                    event.put("name", instance.getName());
                    event.put("state", instance.getState());
                    if (newChars != null) {
                        event.put("characteristic", newChars);
                        log.info("re-provision: service {} → {}", instance.getId(), newChars);
                    }
                    events.publish("ServiceAttributeValueChangeEvent", "service", event);
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
            // A bundle order item is a CONTAINER — the ordering service has already
            // decomposed it into per-component leaf children. Provision only the
            // leaves (each fulfils on its own clock); a parent that carries children
            // is never a service of its own.
            if (item.get("productOrderItem") instanceof List<?> children && !children.isEmpty()) {
                collectItems((List<Map<String, Object>>) children, into);
            } else if (item.get("productOffering") instanceof Map<?, ?> ref && ref.get("id") != null) {
                into.add(item);
            }
        }
    }

    /**
     * Open access: realize a broadband component over a third-party owner's fibre.
     * Ask the qualification service which owners serve this address; if any do,
     * pick one, place the access-seeker order upstream (MEF Sonata shape; mock in
     * dev) and record it. Returns true when wholesale access was placed and active
     * — the retail line is then realized over it, not over our own network.
     */
    @SuppressWarnings("unchecked")
    private boolean provisionWholesaleAccess(String tenant, Map<String, Object> item,
            String serviceId, String owner, String productOrderId) {
        String postCode = postCodeOf(item);
        if (postCode == null || postCode.isBlank()) {
            return false;
        }
        List<Map<String, Object>> options = wholesaleQualification.accessOptions(postCode, "fiber");
        if (options.isEmpty()) {
            return false; // our own network here — nothing to buy
        }
        int requested = requestedBandwidth(item);
        Map<String, Object> pick = pickAccessOption(options, item, requested);
        if (pick == null) {
            return false;
        }
        String accessOwner = String.valueOf(pick.get("accessOwner"));
        String accessLayer = pick.get("accessLayer") == null ? null : String.valueOf(pick.get("accessLayer"));
        Integer bandwidth = pick.get("maxDownMbps") instanceof Number nb ? nb.intValue() : requested;
        String woId = UUID.randomUUID().toString();
        // the async owner callback (Sonata) references OUR order id, so mint it
        // first and hand it over as the buyer reference
        com.bss.som.client.WholesaleAccessClient.AccessOrderResult res =
                wholesaleAccess.order(accessOwner, accessLayer, bandwidth, postCode, serviceId, woId);

        com.bss.som.entity.WholesaleAccessOrder wo = new com.bss.som.entity.WholesaleAccessOrder();
        wo.setId(woId);
        wo.setTenantId(tenant);
        wo.setProductOrderId(productOrderId);
        wo.setServiceId(serviceId);
        wo.setOrderItemId(item.get("id") == null ? null : String.valueOf(item.get("id")));
        wo.setOwnerPartyId(owner);
        wo.setAccessOwner(accessOwner);
        wo.setAccessLayer(accessLayer);
        wo.setBandwidthMbps(bandwidth);
        wo.setPostCode(postCode);
        wo.setExternalId(res.externalId());
        wo.setState(res.state());
        wo.setCreatedAt(OffsetDateTime.now());
        if (com.bss.som.entity.WholesaleAccessOrder.ACTIVE.equals(res.state())) {
            wo.setActivatedAt(OffsetDateTime.now());
        }
        wo.setLastUpdate(OffsetDateTime.now());
        wholesaleOrders.save(wo);
        // the per-line wholesale rate (fail-soft) rides the event so revenue can
        // book the COGS without its own rate lookup
        com.bss.som.client.WholesaleRateCardClient.Rate rate = wholesaleRateCard.rateCard().get(accessOwner);
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("id", wo.getId());
        event.put("accessOwner", accessOwner);
        event.put("accessLayer", accessLayer == null ? "" : accessLayer);
        event.put("state", wo.getState());
        event.put("productOrderId", productOrderId);
        event.put("serviceId", serviceId);
        event.put("externalId", res.externalId());
        if (rate != null) {
            event.put("ratePerLine", rate.perLine());
            event.put("currency", "EUR");
        }
        events.publish("WholesaleAccessOrderStateChangeEvent", "wholesaleAccessOrder", event);
        log.info("wholesale access {}: {} {} {} Mbit/s for retail line {} (owner ref {})",
                wo.getState(), accessOwner, accessLayer, bandwidth, serviceId, res.externalId());
        return com.bss.som.entity.WholesaleAccessOrder.ACTIVE.equals(wo.getState());
    }

    /**
     * The owner's OSS confirmed activation (MEF Sonata notification). Flip the
     * access order active, complete the retail fibre service order + order item so
     * the line comes up and the order rolls up, and publish the active event so
     * revenue books the COGS. Anonymous callback: read across tenants, then act as
     * the order's own tenant. Idempotent — a replayed notification is a no-op.
     */
    @Transactional
    public boolean activateWholesaleAccess(String woId, String sonataOrderId) {
        com.bss.som.entity.WholesaleAccessOrder wo;
        try (var ignored = com.bss.som.security.TenantContext.actAsSystem()) {
            wo = wholesaleOrders.findById(woId).orElse(null);
        }
        if (wo == null || com.bss.som.entity.WholesaleAccessOrder.ACTIVE.equals(wo.getState())) {
            return false;
        }
        try (var ignored = com.bss.som.security.TenantContext.actAs(wo.getTenantId())) {
            wo.setState(com.bss.som.entity.WholesaleAccessOrder.ACTIVE);
            wo.setActivatedAt(OffsetDateTime.now());
            if (sonataOrderId != null && wo.getExternalId() == null) {
                wo.setExternalId(sonataOrderId);
            }
            wo.setLastUpdate(OffsetDateTime.now());
            wholesaleOrders.save(wo);
            // the retail line's service order completes — the provisioned milestone
            if (wo.getServiceId() != null) {
                services.findByIdAndTenantId(wo.getServiceId(), wo.getTenantId()).ifPresent(inst -> {
                    if (inst.getServiceOrderId() != null) {
                        serviceOrders.findByIdAndTenantId(inst.getServiceOrderId(), wo.getTenantId())
                                .ifPresent(so -> {
                                    if (!ServiceOrder.COMPLETED.equals(so.getState())) {
                                        so.setState(ServiceOrder.COMPLETED);
                                        so.setCompletedAt(OffsetDateTime.now());
                                        so.setLastUpdate(OffsetDateTime.now());
                                        serviceOrders.save(so);
                                        events.publish("ServiceOrderStateChangeEvent", "serviceOrder", Map.of(
                                                "id", so.getId(), "state", so.getState(),
                                                "productOrderId", wo.getProductOrderId()));
                                    }
                                });
                    }
                });
            }
            // the retail order item completes — the order rolls up
            if (wo.getProductOrderId() != null && wo.getOrderItemId() != null) {
                ordering.updateItemState(wo.getProductOrderId(), wo.getOrderItemId(), "completed");
            }
            // active event → revenue books the wholesale COGS
            com.bss.som.client.WholesaleRateCardClient.Rate rate =
                    wholesaleRateCard.rateCard().get(wo.getAccessOwner());
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("id", wo.getId());
            event.put("accessOwner", wo.getAccessOwner());
            event.put("accessLayer", wo.getAccessLayer() == null ? "" : wo.getAccessLayer());
            event.put("state", wo.getState());
            event.put("productOrderId", wo.getProductOrderId());
            event.put("serviceId", wo.getServiceId());
            event.put("externalId", wo.getExternalId());
            if (rate != null) {
                event.put("ratePerLine", rate.perLine());
                event.put("currency", "EUR");
            }
            events.publish("WholesaleAccessOrderStateChangeEvent", "wholesaleAccessOrder", event);
            log.info("wholesale access ACTIVATED by owner callback: {} for order {} (line {})",
                    wo.getAccessOwner(), wo.getProductOrderId(), wo.getServiceId());
        }
        return true;
    }

    /** The install/service postcode carried on the component's place. */
    @SuppressWarnings("unchecked")
    private static String postCodeOf(Map<String, Object> item) {
        if (!(item.get("product") instanceof Map<?, ?> product) || product.get("place") == null) {
            return null;
        }
        Object place = product.get("place");
        if (place instanceof List<?> list && !list.isEmpty()) {
            place = list.get(0);
        }
        return place instanceof Map<?, ?> m && m.get("postCode") != null
                ? String.valueOf(m.get("postCode")).replaceAll("\\s", "") : null;
    }

    /** The retail speed the line is sold at — the downloadSpeed characteristic,
     *  else the biggest number in the offering name, else 1000. */
    @SuppressWarnings("unchecked")
    private static int requestedBandwidth(Map<String, Object> item) {
        if (item.get("product") instanceof Map<?, ?> product
                && product.get("productCharacteristic") instanceof List<?> chars) {
            for (Object c : chars) {
                if (c instanceof Map<?, ?> ch && "downloadSpeed".equals(String.valueOf(ch.get("name")))
                        && ch.get("value") != null) {
                    try {
                        return (int) Double.parseDouble(String.valueOf(ch.get("value")));
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
        }
        if (item.get("productOffering") instanceof Map<?, ?> off && off.get("name") != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,4})")
                    .matcher(String.valueOf(off.get("name")));
            int best = 0;
            while (m.find()) {
                best = Math.max(best, Integer.parseInt(m.group(1)));
            }
            if (best > 0) {
                return best;
            }
        }
        return 1000;
    }

    /**
     * Choose the access owner. If the order names a preferred owner (a productChar
     * accessOwner — the retailer's pick), honour it; otherwise the efficient
     * allocation: the SMALLEST bandwidth tier that still meets the retail speed
     * (headroom without waste), falling back to the biggest available if none reach it.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> pickAccessOption(List<Map<String, Object>> options,
            Map<String, Object> item, int requested) {
        String preferred = null;
        if (item.get("product") instanceof Map<?, ?> product
                && product.get("productCharacteristic") instanceof List<?> chars) {
            for (Object c : chars) {
                if (c instanceof Map<?, ?> ch && "accessOwner".equals(String.valueOf(ch.get("name")))
                        && ch.get("value") != null) {
                    preferred = String.valueOf(ch.get("value"));
                }
            }
        }
        if (preferred != null) {
            for (Map<String, Object> o : options) {
                if (preferred.equalsIgnoreCase(String.valueOf(o.get("accessOwner")))) {
                    return o;
                }
            }
        }
        Map<String, Object> meets = null;
        Map<String, Object> biggest = null;
        for (Map<String, Object> o : options) {
            int bw = o.get("maxDownMbps") instanceof Number n ? n.intValue() : 0;
            if (biggest == null || bw > (Integer) biggest.getOrDefault("maxDownMbps", 0)) {
                biggest = o;
            }
            if (bw >= requested && (meets == null
                    || bw < (Integer) meets.getOrDefault("maxDownMbps", Integer.MAX_VALUE))) {
                meets = o;
            }
        }
        return meets != null ? meets : biggest;
    }

    /** Coarse product family from a catalog category — mirrors the ordering
     *  service's decomposition axis, so SOM fulfils each component by its class
     *  (a handset never draws a phone number; broadband installs; TV is digital). */
    static String componentType(String category) {
        String c = category == null ? "" : category.toLowerCase();
        if (c.contains("broadband") || c.contains("internet") || c.contains("fiber") || c.contains("fibre")) {
            return "internet";
        }
        if (c.contains("tv") || c.contains("entertainment") || c.contains("streaming")) {
            return "tv";
        }
        if (c.contains("mobile")) {
            return "mobile";
        }
        if (c.contains("device") || c.contains("handset") || c.contains("phone")) {
            return "device";
        }
        return "other";
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
        if (!tickGuard.claim("resume-due", java.time.Duration.ofSeconds(60))) {
            return; // another replica lifts the holds
        }
        try {
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
        } finally {
            tickGuard.release("resume-due");
        }
    }

    /** Choose-your-number (task #198): a shortlist of AVAILABLE numbers from the
     * window ahead of the pool counter — previewed, never consumed. The same
     * shuffle seed returns the same list (stable while the shopper thinks);
     * a new seed deals a fresh hand, the Verizon-style "show me others". */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> offerNumbers(String tenant, int count, String shuffle) {
        return pools.findFirstByTenantIdAndResourceTypeOrderByIdAsc(tenant, com.bss.som.entity.ResourcePool.MSISDN)
                .map(pool -> {
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    long base = pool.getNextValue();
                    java.util.Random rnd = new java.util.Random(
                            java.util.Objects.hash(base, shuffle == null ? "" : shuffle));
                    java.util.Set<Long> tried = new java.util.HashSet<>();
                    while (out.size() < count && tried.size() < 400) {
                        long offset = rnd.nextInt(400);
                        if (!tried.add(offset)) {
                            continue;
                        }
                        String candidate = pool.getPrefix() + String.format("%06d", base + offset);
                        if (assignments.findFirstByTenantIdAndValue(tenant, candidate).isEmpty()) {
                            out.add(Map.of("msisdn", candidate));
                        }
                    }
                    return out;
                })
                .orElse(List.of());
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
        // Release the assigned number — onto the QUARANTINE ledger with its
        // story (a ported-out number now lives with the rival; a cancelled
        // one ages on the shelf; either way the departure is auditable).
        String releasedNumber = null;
        for (ResourceAssignment a : assignments.findByTenantIdAndServiceId(
                instance.getTenantId(), instance.getId())) {
            releasedNumber = a.getValue();
            assignments.delete(a);
            com.bss.som.entity.NumberQuarantine shelf = new com.bss.som.entity.NumberQuarantine();
            shelf.setId(UUID.randomUUID().toString());
            shelf.setTenantId(instance.getTenantId());
            shelf.setNumber(a.getValue());
            shelf.setServiceId(instance.getId());
            shelf.setReason(reason != null && reason.length() > 32 ? reason.substring(0, 32) : reason);
            shelf.setReleasedAt(OffsetDateTime.now());
            quarantine.save(shelf);
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
        clawBackCommission(instance.getTenantId(), instance.getId());
        events.publish("ServiceTerminatedEvent", "service", event);
        log.info("terminated service {} for {} ({})", instance.getName(), instance.getOwnerPartyId(), reason);
        return event;
    }

}
