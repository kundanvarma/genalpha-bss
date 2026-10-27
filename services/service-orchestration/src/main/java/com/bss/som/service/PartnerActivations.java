package com.bss.som.service;

import com.bss.som.api.ApiConstants;
import com.bss.som.client.OrderingClient;
import com.bss.som.client.PartnerEntitlementClient;
import com.bss.som.client.PartnerEntitlementClient.Activation;
import com.bss.som.entity.PartnerActivation;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.repository.PartnerActivationRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.security.TenantRegistry;
import com.bss.som.tick.TickGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Partner activations, honest about time (#355). A partner may confirm at
 * once, later, or refuse — and a timeout means "we do not know yet", never
 * "it failed". So:
 * <ul>
 * <li>active at once: the code is held and the service is up, as before;</li>
 * <li>pending or unknown: the service waits ({@code inactive}), the order item
 * stays inProgress, and the sweep settles it;</li>
 * <li>refused: the service never comes up and the item fails, with the
 * partner's own reason.</li>
 * </ul>
 * The sweep ASKS the partner before it ever sends again, and the request key
 * (the service id) is what the partner dedupes on — so a late success
 * converges on one active service and one activation, never two. Ending a
 * partner service tells the partner; when the partner cannot be told, the
 * ending still stands here and the sweep keeps telling it until it hears.
 */
@Service
public class PartnerActivations {

    private static final Logger log = LoggerFactory.getLogger(PartnerActivations.class);
    private static final List<String> OPEN = List.of(PartnerActivation.PENDING, PartnerActivation.DEACTIVATION_PENDING);

    /** What starting an activation came to, for the order loop. */
    public record Started(PartnerEntitlementClient.State state, String code, String reason) {
    }

    private final PartnerEntitlementClient partners;
    private final LineProvisioning lines;
    private final ServiceInstanceRepository services;
    private final ServiceOrderRepository serviceOrders;
    private final PartnerActivationRepository activations;
    private final OrderingClient ordering;
    private final TenantRegistry tenants;
    private final TickGuard tickGuard;
    private final DomainEventPublisher events;

    public PartnerActivations(PartnerEntitlementClient partners, LineProvisioning lines,
            ServiceInstanceRepository services, ServiceOrderRepository serviceOrders,
            PartnerActivationRepository activations, OrderingClient ordering, TenantRegistry tenants,
            TickGuard tickGuard, DomainEventPublisher events) {
        this.partners = partners;
        this.lines = lines;
        this.services = services;
        this.serviceOrders = serviceOrders;
        this.activations = activations;
        this.ordering = ordering;
        this.tenants = tenants;
        this.tickGuard = tickGuard;
        this.events = events;
    }

    /** Ask the partner to activate the service just created for an order item. */
    public Started start(String tenant, String serviceId, String owner, String offeringName,
            String productOrderId, String orderItemId) {
        Activation a = partners.activate(tenant, serviceId, offeringName, owner);
        switch (a.state()) {
            case ACTIVE -> {
                lines.assignPartnerCode(tenant, serviceId, owner, a.code());
                return new Started(PartnerEntitlementClient.State.ACTIVE, a.code(), null);
            }
            case REFUSED -> {
                record(tenant, serviceId, owner, offeringName, productOrderId, orderItemId,
                        PartnerActivation.REFUSED, a.reason());
                services.findByIdAndTenantId(serviceId, tenant).ifPresent(s -> setState(s, ServiceInstance.INACTIVE));
                log.info("partner refused '{}' for {}: {}", offeringName, owner, a.reason());
                return new Started(PartnerEntitlementClient.State.REFUSED, null, a.reason());
            }
            default -> {
                // pending, or no answer in time: the service waits and the sweep asks
                record(tenant, serviceId, owner, offeringName, productOrderId, orderItemId,
                        PartnerActivation.PENDING, a.reason());
                services.findByIdAndTenantId(serviceId, tenant).ifPresent(s -> setState(s, ServiceInstance.INACTIVE));
                log.info("partner activation of '{}' for {} is {} — the sweep will settle it", offeringName, owner,
                        a.state() == PartnerEntitlementClient.State.PENDING ? "pending" : "unconfirmed (" + a.reason() + ")");
                return new Started(a.state(), null, a.reason());
            }
        }
    }

    public Optional<PartnerActivation> of(String tenant, String serviceId) {
        return activations.findByTenantIdAndServiceId(tenant, serviceId);
    }

    /**
     * End a partner service with the partner. When the partner cannot be told,
     * the ending stands here and the row queues the telling for the sweep.
     */
    public void deactivate(ServiceInstance instance, String activationCode) {
        String tenant = instance.getTenantId();
        try {
            partners.deactivate(tenant, instance.getId(), activationCode, instance.getOwnerPartyId());
            of(tenant, instance.getId()).ifPresent(row -> touch(row, PartnerActivation.DEACTIVATED, null));
        } catch (RuntimeException e) {
            PartnerActivation row = of(tenant, instance.getId()).orElseGet(() -> record(tenant, instance.getId(),
                    instance.getOwnerPartyId(), instance.getName(), null, null,
                    PartnerActivation.DEACTIVATION_PENDING, null));
            row.setReason(activationCode);   // the code the partner must still stop
            touch(row, PartnerActivation.DEACTIVATION_PENDING, activationCode);
            log.warn("partner could not deactivate {} ({}): ended here, the sweep keeps telling the partner",
                    instance.getName(), e.getMessage());
        }
    }

    /** Settle what is open, every tenant, one replica at a time. */
    @Scheduled(fixedDelayString = "${bss.partner.sweep-ms:3000}")
    public void sweep() {
        if (!tickGuard.claim("partner-activation-sweep", Duration.ofSeconds(60))) {
            return;
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (var ignored = TenantContext.actAs(tenant.getId())) {
                    for (PartnerActivation row : activations.findTop100ByTenantIdAndStateInOrderByLastUpdateAsc(
                            tenant.getId(), OPEN)) {
                        settle(row);
                    }
                } catch (RuntimeException e) {
                    log.warn("partner sweep failed for {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("partner-activation-sweep");
        }
    }

    void settle(PartnerActivation row) {
        String tenant = row.getTenantId();
        if (PartnerActivation.DEACTIVATION_PENDING.equals(row.getState())) {
            try {
                partners.deactivate(tenant, row.getServiceId(), row.getReason(), row.getOwnerPartyId());
                touch(row, PartnerActivation.DEACTIVATED, null);
                log.info("partner finally deactivated {} for {}", row.getOfferingName(), row.getOwnerPartyId());
            } catch (RuntimeException e) {
                row.setAttempts(row.getAttempts() + 1);
                touch(row, PartnerActivation.DEACTIVATION_PENDING, row.getReason());
            }
            return;
        }
        // ASK FIRST: whatever the partner already did wins
        Activation now = partners.status(tenant, row.getServiceId());
        if (now.state() == PartnerEntitlementClient.State.UNKNOWN) {
            // the partner never received it: send again, under the same key
            row.setAttempts(row.getAttempts() + 1);
            now = partners.activate(tenant, row.getServiceId(), row.getOfferingName(), row.getOwnerPartyId());
        }
        switch (now.state()) {
            case ACTIVE -> confirmed(row, now.code());
            case REFUSED -> refused(row, now.reason());
            default -> touch(row, PartnerActivation.PENDING, row.getReason());
        }
    }

    private void confirmed(PartnerActivation row, String code) {
        String tenant = row.getTenantId();
        lines.assignPartnerCode(tenant, row.getServiceId(), row.getOwnerPartyId(), code);
        touch(row, PartnerActivation.ACTIVE, null);
        services.findByIdAndTenantId(row.getServiceId(), tenant).ifPresent(s -> {
            setState(s, ServiceInstance.ACTIVE);
            serviceOrders.findByIdAndTenantId(s.getServiceOrderId(), tenant).ifPresent(so -> close(so, ServiceOrder.COMPLETED));
        });
        report(row, "completed");
        log.info("partner confirmed {} for {} (code {})", row.getOfferingName(), row.getOwnerPartyId(), code);
    }

    private void refused(PartnerActivation row, String reason) {
        String tenant = row.getTenantId();
        row.setReason(reason);
        touch(row, PartnerActivation.REFUSED, reason);
        services.findByIdAndTenantId(row.getServiceId(), tenant).ifPresent(s ->
                serviceOrders.findByIdAndTenantId(s.getServiceOrderId(), tenant).ifPresent(so -> close(so, ServiceOrder.FAILED)));
        report(row, "failed");
        log.info("partner refused {} for {}: {}", row.getOfferingName(), row.getOwnerPartyId(), reason);
    }

    /** Close the order loop: the item lands (with its realising service) or fails. */
    private void report(PartnerActivation row, String state) {
        if (row.getProductOrderId() == null || row.getOrderItemId() == null) {
            return;
        }
        Map<String, Object> realizing = new LinkedHashMap<>();
        realizing.put("id", row.getServiceId());
        realizing.put("href", ApiConstants.INVENTORY_BASE + "/service/" + row.getServiceId());
        realizing.put("name", row.getOfferingName());
        realizing.put("@referredType", "Service");
        ordering.updateItemState(row.getProductOrderId(), row.getOrderItemId(), state,
                "completed".equals(state) ? realizing : null);
    }

    private void close(ServiceOrder so, String state) {
        so.setState(state);
        if (ServiceOrder.COMPLETED.equals(state)) {
            so.setCompletedAt(OffsetDateTime.now());
        }
        so.setLastUpdate(OffsetDateTime.now());
        serviceOrders.save(so);
        events.publish("ServiceOrderStateChangeEvent", "serviceOrder", Map.of(
                "id", so.getId(), "state", state, "productOrderId", so.getProductOrderId()));
    }

    private void setState(ServiceInstance s, String state) {
        s.setState(state);
        s.setLastUpdate(OffsetDateTime.now());
        services.save(s);
    }

    private PartnerActivation record(String tenant, String serviceId, String owner, String offeringName,
            String productOrderId, String orderItemId, String state, String reason) {
        PartnerActivation row = new PartnerActivation();
        row.setTenantId(tenant);
        row.setId(UUID.randomUUID().toString());
        row.setServiceId(serviceId);
        row.setOwnerPartyId(owner);
        row.setOfferingName(offeringName == null ? "partner service" : offeringName);
        row.setProductOrderId(productOrderId);
        row.setOrderItemId(orderItemId);
        row.setState(state);
        row.setReason(reason);
        row.setAttempts(1);
        row.setCreatedAt(OffsetDateTime.now());
        row.setLastUpdate(OffsetDateTime.now());
        return activations.save(row);
    }

    private void touch(PartnerActivation row, String state, String reason) {
        row.setState(state);
        row.setReason(reason);
        row.setLastUpdate(OffsetDateTime.now());
        activations.save(row);
    }
}
