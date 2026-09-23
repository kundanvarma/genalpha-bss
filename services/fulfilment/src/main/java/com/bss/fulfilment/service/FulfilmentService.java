package com.bss.fulfilment.service;

import com.bss.fulfilment.client.LogisticsClient;
import com.bss.fulfilment.client.OrderingClient;
import com.bss.fulfilment.dto.AppointmentRef;
import com.bss.fulfilment.dto.CarrierEvent;
import com.bss.fulfilment.dto.PartyRef;
import com.bss.fulfilment.dto.ShippingOrderView;
import com.bss.fulfilment.dto.ShippingPatch;
import com.bss.fulfilment.dto.WorkOrderView;
import com.bss.fulfilment.dto.WorkPatch;
import com.bss.fulfilment.entity.ShippingOrder;
import com.bss.fulfilment.entity.WorkOrder;
import com.bss.fulfilment.events.DomainEventPublisher;
import com.bss.fulfilment.exception.BadRequestException;
import com.bss.fulfilment.exception.NotFoundException;
import com.bss.fulfilment.repository.ShippingOrderRepository;
import com.bss.fulfilment.repository.WorkOrderRepository;
import com.bss.fulfilment.security.PartyScope;
import com.bss.fulfilment.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF700 + TMF697: the parcel and the visit as resources. Born from the
 * order and appointment events the fleet already publishes; advanced by
 * the warehouse/installer over the API; and when the parcel is DELIVERED
 * and any visit is COMPLETED, the product order completes under this
 * service's own machine identity — the CSR button becomes optional, not
 * load-bearing.
 */
@Service
public class FulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);
    private static final List<String> SHIPPING_STATES = List.of(ShippingOrder.ACKNOWLEDGED,
            ShippingOrder.IN_PROGRESS, ShippingOrder.SHIPPED, ShippingOrder.DELIVERED,
            ShippingOrder.CANCELLED);
    private static final List<String> WORK_STATES = List.of(WorkOrder.PLANNED,
            WorkOrder.IN_PROGRESS, WorkOrder.COMPLETED, WorkOrder.CANCELLED);

    private final ShippingOrderRepository shippingOrders;
    private final WorkOrderRepository workOrders;
    private final OrderingClient ordering;
    private final com.bss.fulfilment.client.CarrierRouter carrierRouter;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final String carrierCallbackUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FulfilmentService(ShippingOrderRepository shippingOrders, WorkOrderRepository workOrders,
            OrderingClient ordering, com.bss.fulfilment.client.CarrierRouter carrierRouter,
            DomainEventPublisher events, TenantScope tenantScope, PartyScope partyScope,
            @org.springframework.beans.factory.annotation.Value(
                "${bss.fulfilment.carrier-callback-url:http://fulfilment:8080/tmf-api/shippingOrderManagement/v4/carrierEvent}")
            String carrierCallbackUrl) {
        this.shippingOrders = shippingOrders;
        this.workOrders = workOrders;
        this.ordering = ordering;
        this.carrierRouter = carrierRouter;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.carrierCallbackUrl = carrierCallbackUrl;
    }

    /* ---------- births (listener-called, acting as tenant) ---------- */

    @Transactional
    public void onPhysicalOrder(String orderId, String partyId, Object items, Object place) {
        String tenant = tenantScope.currentTenantId();
        com.bss.fulfilment.client.DeliveryChoice delivery = deliveryFrom(place);
        if (shippingOrders.findByTenantIdAndProductOrderId(tenant, orderId).isPresent()) {
            return; // at-least-once delivery
        }
        ShippingOrder so = new ShippingOrder();
        so.setId(UUID.randomUUID().toString());
        so.setTenantId(tenant);
        so.setProductOrderId(orderId);
        so.setOwnerPartyId(partyId);
        so.setState(ShippingOrder.ACKNOWLEDGED);
        so.setItemsJson(writeJson(items));
        so.setPlaceJson(writeJson(place));
        so.setDeliveryMethod(delivery.method());
        so.setCreatedAt(OffsetDateTime.now());
        so.setLastUpdate(OffsetDateTime.now());
        shippingOrders.save(so);
        events.publish("ShippingOrderCreateEvent", "shippingOrder", shippingView(so));
        log.info("fulfilment: shippingOrder {} minted for order {}", so.getId(), orderId);

        // C2 — hand the parcel to the carrier (Helthjem seam). Fail-open: if the
        // seam is off or the carrier is down, book() returns null and the parcel
        // waits for the manual warehouse flow, exactly as before.
        LogisticsClient.Booking booked = carrierRouter.book(tenant, LogisticsClient.Booking.request(
                so.getId(), tenant, carrierCallbackUrl, partyId,
                delivery.isPickup() ? "PICKUP_POINT" : "HOME_STANDARD"), delivery, postcodeFrom(place));
        if (booked != null && booked.trackingNumber() != null) {
            so.setTrackingRef(booked.trackingNumber());
            so.setCarrier(booked.carrier());
            if (delivery.isPickup()) {
                so.setPickupPoint(delivery.pickupPointName());
            }
            so.setState(ShippingOrder.IN_PROGRESS);
            so.setLastUpdate(OffsetDateTime.now());
            shippingOrders.save(so);
            events.publish("ShippingOrderStateChangeEvent", "shippingOrder", shippingView(so));
            log.info("fulfilment: shippingOrder {} booked with {} — tracking {}",
                    so.getId(), booked.carrier(), booked.trackingNumber());
        }
    }

    /**
     * C2 — the carrier reports a parcel's status (delivery callback here; a
     * real Helthjem adapter would poll getTracking). On DELIVERED we mark the
     * shipping order delivered and complete each shipped ITEM on the product
     * order — the parent order rolls up from there.
     */
    @Transactional
    public void onCarrierEvent(CarrierEvent body) {
        CarrierEvent event = body == null ? CarrierEvent.EMPTY : body;
        String tenant = event.tenant("genalpha");
        String shippingOrderId = event.parcelId();
        String status = event.statusValue();
        try (com.bss.fulfilment.security.TenantContext ignored =
                com.bss.fulfilment.security.TenantContext.actAs(tenant)) {
            ShippingOrder so = shippingOrders.findByIdAndTenantId(shippingOrderId, tenant).orElse(null);
            if (so == null) {
                log.warn("fulfilment: carrier event for unknown shippingOrder {}", shippingOrderId);
                return;
            }
            if (ShippingOrder.DELIVERED.equals(so.getState()) || ShippingOrder.CANCELLED.equals(so.getState())) {
                return; // terminal; carrier events are at-least-once
            }
            if ("DELIVERED".equalsIgnoreCase(status)) {
                so.setState(ShippingOrder.DELIVERED);
                so.setLastUpdate(OffsetDateTime.now());
                shippingOrders.save(so);
                events.publish("ShippingOrderStateChangeEvent", "shippingOrder", shippingView(so));
                completeShippedItems(so);
                maybeCompleteOrder(tenant, so.getProductOrderId());
                log.info("fulfilment: parcel {} DELIVERED by carrier; shipped items completed", shippingOrderId);
            } else if ("IN_TRANSIT".equalsIgnoreCase(status) && ShippingOrder.ACKNOWLEDGED.equals(so.getState())) {
                so.setState(ShippingOrder.IN_PROGRESS);
                so.setLastUpdate(OffsetDateTime.now());
                shippingOrders.save(so);
            }
        }
    }

    /** Complete each shipped item on the product order (C2 per-item rollup). */
    private void completeShippedItems(ShippingOrder so) {
        JsonNode items = readJson(so.getItemsJson());
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (item.isObject() && item.hasNonNull("id")) {
                ordering.updateItemState(so.getProductOrderId(), item.get("id").asText(), "completed");
            }
        }
    }

    @Transactional
    public void onInstallAppointment(String appointmentId, String orderId, String partyId, Object place) {
        String tenant = tenantScope.currentTenantId();
        if (workOrders.findByTenantIdAndProductOrderId(tenant, orderId).isPresent()) {
            return;
        }
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID().toString());
        wo.setTenantId(tenant);
        wo.setProductOrderId(orderId);
        wo.setAppointmentId(appointmentId);
        wo.setOwnerPartyId(partyId);
        wo.setState(WorkOrder.PLANNED);
        wo.setPlaceJson(writeJson(place));
        wo.setCreatedAt(OffsetDateTime.now());
        wo.setLastUpdate(OffsetDateTime.now());
        workOrders.save(wo);
        events.publish("WorkOrderCreateEvent", "workOrder", workView(wo));
        log.info("fulfilment: workOrder {} minted for order {} (appointment {})",
                wo.getId(), orderId, appointmentId);
    }

    /* ---------- the warehouse / installer face ---------- */

    @Transactional
    public ShippingOrderView patchShipping(String id, ShippingPatch body) {
        // customers hold ordering:write for their OWN orders — but nobody
        // party-scoped drives the warehouse. Staff/partner machines only.
        partyScope.scopedPartyId().ifPresent(p -> {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "fulfilment state changes are staff-only");
        });
        String tenant = tenantScope.currentTenantId();
        ShippingOrder so = shippingOrders.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("ShippingOrder", id));
        ShippingPatch dto = body == null ? ShippingPatch.EMPTY : body;
        String state = dto.stateValue();
        if (!SHIPPING_STATES.contains(state)) {
            throw new BadRequestException("state must be one of " + SHIPPING_STATES);
        }
        if (ShippingOrder.DELIVERED.equals(so.getState()) || ShippingOrder.CANCELLED.equals(so.getState())) {
            throw new BadRequestException("shipping order is terminal (" + so.getState() + ")");
        }
        so.setState(state);
        if (dto.trackingRefValue() != null) {
            so.setTrackingRef(dto.trackingRefValue());
        }
        so.setLastUpdate(OffsetDateTime.now());
        shippingOrders.save(so);
        events.publish("ShippingOrderStateChangeEvent", "shippingOrder", shippingView(so));
        maybeCompleteOrder(tenant, so.getProductOrderId());
        return shippingView(so);
    }

    @Transactional
    public WorkOrderView patchWork(String id, WorkPatch body) {
        // customers hold ordering:write for their OWN orders — but nobody
        // party-scoped drives the warehouse. Staff/partner machines only.
        partyScope.scopedPartyId().ifPresent(p -> {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "fulfilment state changes are staff-only");
        });
        String tenant = tenantScope.currentTenantId();
        WorkOrder wo = workOrders.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("WorkOrder", id));
        WorkPatch dto = body == null ? WorkPatch.EMPTY : body;
        String state = dto.stateValue();
        if (!WORK_STATES.contains(state)) {
            throw new BadRequestException("state must be one of " + WORK_STATES);
        }
        if (WorkOrder.COMPLETED.equals(wo.getState()) || WorkOrder.CANCELLED.equals(wo.getState())) {
            throw new BadRequestException("work order is terminal (" + wo.getState() + ")");
        }
        wo.setState(state);
        if (dto.noteValue() != null) {
            wo.setNote(dto.noteValue());
        }
        wo.setLastUpdate(OffsetDateTime.now());
        workOrders.save(wo);
        events.publish("WorkOrderStateChangeEvent", "workOrder", workView(wo));
        maybeCompleteOrder(tenant, wo.getProductOrderId());
        return workView(wo);
    }

    /** The completion rule: parcel DELIVERED and any visit COMPLETED →
     * the product order completes, machine-driven. The ordering side's
     * terminal-state guard makes this race-safe with the CSR button. */
    private void maybeCompleteOrder(String tenant, String orderId) {
        boolean delivered = shippingOrders.findByTenantIdAndProductOrderId(tenant, orderId)
                .map(s -> ShippingOrder.DELIVERED.equals(s.getState())).orElse(true);
        boolean visitDone = workOrders.findByTenantIdAndProductOrderId(tenant, orderId)
                .map(w -> WorkOrder.COMPLETED.equals(w.getState())
                        || WorkOrder.CANCELLED.equals(w.getState()))
                .orElse(true);
        if (delivered && visitDone) {
            try {
                ordering.completeOrder(orderId);
                log.info("fulfilment: order {} COMPLETED (parcel delivered, visit done)", orderId);
            } catch (Exception e) {
                log.warn("fulfilment: order {} completion failed (will not retry here): {}",
                        orderId, e.getMessage());
            }
        }
    }

    /* ---------- reads (party-scoped: track-my-delivery for free) ---------- */

    @Transactional(readOnly = true)
    public List<ShippingOrderView> listShipping() {
        String tenant = tenantScope.currentTenantId();
        return partyScope.scopedPartyId()
                .map(own -> shippingOrders.findByTenantIdAndOwnerPartyIdOrderByCreatedAtDesc(tenant, own))
                .orElseGet(() -> shippingOrders.findTop100ByTenantIdOrderByCreatedAtDesc(tenant))
                .stream().map(this::shippingView).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkOrderView> listWork() {
        String tenant = tenantScope.currentTenantId();
        return partyScope.scopedPartyId()
                .map(own -> workOrders.findByTenantIdAndOwnerPartyIdOrderByCreatedAtDesc(tenant, own))
                .orElseGet(() -> workOrders.findTop100ByTenantIdOrderByCreatedAtDesc(tenant))
                .stream().map(this::workView).toList();
    }

    @Transactional(readOnly = true)
    public ShippingOrderView shippingById(String id) {
        ShippingOrder so = shippingOrders.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("ShippingOrder", id));
        requireOwn(so.getOwnerPartyId(), "ShippingOrder", id);
        return shippingView(so);
    }

    @Transactional(readOnly = true)
    public WorkOrderView workById(String id) {
        WorkOrder wo = workOrders.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("WorkOrder", id));
        requireOwn(wo.getOwnerPartyId(), "WorkOrder", id);
        return workView(wo);
    }

    private void requireOwn(String owner, String resource, String id) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(owner)) {
                throw NotFoundException.forResource(resource, id);
            }
        });
    }

    /* ---------- views ---------- */

    /** The shopper's delivery choice rides the delivery place from checkout. */
    @SuppressWarnings("unchecked")
    private com.bss.fulfilment.client.DeliveryChoice deliveryFrom(Object place) {
        Object first = place instanceof List<?> list && !list.isEmpty() ? list.get(0) : place;
        if (first instanceof Map<?, ?> m && m.get("deliveryMethod") != null) {
            return new com.bss.fulfilment.client.DeliveryChoice(String.valueOf(m.get("deliveryMethod")),
                    strOrNull(m.get("carrier")), strOrNull(m.get("pickupPointId")), strOrNull(m.get("pickupPointName")));
        }
        return com.bss.fulfilment.client.DeliveryChoice.HOME;
    }

    private static String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** The delivery postcode drives rule-based carrier routing (C-P4). */
    private String postcodeFrom(Object place) {
        Object first = place instanceof List<?> list && !list.isEmpty() ? list.get(0) : place;
        if (first instanceof Map<?, ?> m) {
            Object pc = m.get("postCode") != null ? m.get("postCode") : m.get("postcode");
            return pc == null ? null : String.valueOf(pc);
        }
        return null;
    }

    /** Carrier "track & trace" URL for a parcel — a config-overridable template
     * ({tracking} substituted), else a well-known Nordic-carrier pattern. */
    private String trackingUrlFor(ShippingOrder so) {
        if (so.getTrackingRef() == null || so.getCarrier() == null) {
            return null;
        }
        String ref = so.getTrackingRef();
        // Well-known Nordic carriers, keyed on the display name. (A per-tenant
        // config template is a documented follow-up — carrier_config.config.)
        String c = so.getCarrier().toLowerCase();
        if (c.contains("bring") || c.contains("posten")) {
            return "https://tracking.bring.com/tracking/" + ref;
        }
        if (c.contains("postnord")) {
            return "https://www.postnord.no/en/track-and-trace?shipmentId=" + ref;
        }
        if (c.contains("helthjem")) {
            return "https://helthjem.no/sporing?q=" + ref;
        }
        return null;
    }

    private ShippingOrderView shippingView(ShippingOrder so) {
        // trackingUrl is a real "Track your parcel" deep-link into the carrier's own
        // app, so a customer follows the parcel where they always would.
        return new ShippingOrderView(
                so.getId(),
                "/tmf-api/shippingOrderManagement/v4/shippingOrder/" + so.getId(),
                so.getProductOrderId(),
                so.getState(),
                readJson(so.getItemsJson()),
                readJson(so.getPlaceJson()),
                so.getTrackingRef(),
                so.getCarrier(),
                trackingUrlFor(so),
                so.getDeliveryMethod(),
                so.getPickupPoint(),
                so.getOwnerPartyId() == null ? null : List.of(PartyRef.customer(so.getOwnerPartyId())),
                so.getCreatedAt());
    }

    private WorkOrderView workView(WorkOrder wo) {
        return new WorkOrderView(
                wo.getId(),
                "/tmf-api/shippingOrderManagement/v4/workOrder/" + wo.getId(),
                wo.getProductOrderId(),
                wo.getAppointmentId() == null ? null : AppointmentRef.of(wo.getAppointmentId()),
                wo.getState(),
                readJson(wo.getPlaceJson()),
                wo.getNote(),
                wo.getOwnerPartyId() == null ? null : List.of(PartyRef.customer(wo.getOwnerPartyId())),
                wo.getCreatedAt());
    }

    private String writeJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** An absent or unreadable column answered an empty list; it still does. */
    private JsonNode readJson(String s) {
        try {
            return s == null ? objectMapper.createArrayNode() : objectMapper.readTree(s);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }
}
